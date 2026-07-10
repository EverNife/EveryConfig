package br.com.finalcraft.everyconfig.config;

import br.com.finalcraft.everyconfig.io.AtomicFileBackStore;
import br.com.finalcraft.everyconfig.io.BackStore;
import br.com.finalcraft.everyconfig.io.ConfigExecutors;
import br.com.finalcraft.everyconfig.binding.BindOptions;
import br.com.finalcraft.everyconfig.binding.BindResult;
import br.com.finalcraft.everyconfig.binding.EntityBinder;
import br.com.finalcraft.everyconfig.binding.merge.ElementStringList;
import br.com.finalcraft.everyconfig.binding.merge.KeyIndexer;
import br.com.finalcraft.everyconfig.binding.merge.LifecycleGraphWalker;
import br.com.finalcraft.everyconfig.binding.merge.LifecycleInvoker;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CommentAware;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.CodecException;
import br.com.finalcraft.everyconfig.codec.CodecRegistry;
import br.com.finalcraft.everyconfig.codec.jackson.InMemoryCodec;
import br.com.finalcraft.everyconfig.config.section.ConfigSection;
import br.com.finalcraft.everyconfig.core.KeyOrder;
import br.com.finalcraft.everyconfig.core.coerce.NodeCoercion;
import br.com.finalcraft.everyconfig.core.coerce.TypeFamily;
import br.com.finalcraft.everyconfig.core.comment.CommentTree;
import br.com.finalcraft.everyconfig.core.comment.CommentType;
import br.com.finalcraft.everyconfig.core.tree.DPath;
import br.com.finalcraft.everyconfig.io.watcher.Watcher;
import br.com.finalcraft.everyconfig.io.watcher.Fingerprint;
import br.com.finalcraft.everyconfig.selfdescribe.CompactElementCodec;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The dynamic configuration handle: a thin wrapper over a canonical, mutable Jackson {@link ObjectNode}
 * (the single source of truth) plus a sibling {@link CommentTree} and a captured {@link KeyOrder}. It
 * carries the full dynamic path API ({@code setValue}/{@code getValue}/typed getters/{@code getKeys}/
 * {@code getOrSetValueIfAbsent}), the typed entity binding ({@code bind}/{@code loadAs}) as a derived view,
 * and the back-store-backed lifecycle ({@code open}/{@code save}/{@code reload}/{@code close}).
 *
 * <p>Single-writer-by-convention: concurrent reads are fine; concurrent writes (or a write racing a
 * read) require caller synchronization. {@link #getRoot()} exposes the tree directly as the escape
 * hatch for callers that want raw Jackson access.
 */
public class Config implements AutoCloseable {

    private ObjectNode root;
    private CommentTree comments;
    private KeyOrder fileKeyOrder;
    private final JsonNodeFactory nodes;
    private final NodeCoercion coercion;

    // ---- lifecycle (null for an in-memory Config not opened over a file) ----
    private final ReentrantLock lock = new ReentrantLock(true);
    private BackStore backStore;
    private Codec codec;
    private volatile Fingerprint loaded = Fingerprint.ABSENT;
    private volatile LoadStatus lastLoadStatus = LoadStatus.NEVER_LOADED;
    private boolean dirty = false;
    private Watcher watcher;
    private volatile Runnable onReload;

    // "The file's shape evolved": flipped true whenever a default (a value or comment the file lacked) is
    // seeded. Distinct from `dirty`, which means "there is unsaved work" for ANY mutation — this tracks
    // only default-seeding, so a caller can tell "I completed an old file with new keys" apart from "a
    // value was edited". (Not serialized state — a Config is never serialized.)
    private boolean newSeededDefaults = false;

    // In-memory ordering policy: pin directives keyed by full dotted path (last write wins). Re-applied onto
    // the captured KeyOrder at every save (see encodeWith) so a key stays FIRST/LAST regardless of later
    // seeding; never written to the file. Populated by pinFirst/pinLast/unpin.
    private final Map<String, KeyOrder.Zone> pins = new LinkedHashMap<>();

    public Config() {
        this(JsonNodeFactory.instance.objectNode(), new CommentTree(), KeyOrder.empty());
    }

    public Config(final ObjectNode root) {
        this(root, new CommentTree(), KeyOrder.empty());
    }

    public Config(final ObjectNode root, final CommentTree comments, final KeyOrder fileKeyOrder) {
        if (root == null) {
            throw new IllegalArgumentException("root ObjectNode cannot be null");
        }
        this.root = root;
        this.comments = comments != null ? comments : new CommentTree();
        this.fileKeyOrder = fileKeyOrder != null ? fileKeyOrder : KeyOrder.empty();
        this.nodes = JsonNodeFactory.instance;
        this.coercion = new NodeCoercion(this.nodes);
    }

    // ==================== escape hatch + internals ====================

    /** The live canonical tree; callers may read or mutate it directly (it is the source of truth). */
    public ObjectNode getRoot() {
        return root;
    }

    public CommentTree getCommentTree() {
        return comments;
    }

    public KeyOrder getFileKeyOrder() {
        return fileKeyOrder;
    }

    public NodeCoercion getCoercion() {
        return coercion;
    }

    /**
     * The codec this Config binds and persists with, or {@code null} for a bare {@code new Config()} that
     * was never given one. {@link #open} sets a file codec, {@link #inMemory()} sets {@code InMemoryCodec},
     * and {@link #changeCodec(Codec)} swaps it.
     */
    public Codec getCodec() {
        return codec;
    }

    /**
     * The file this Config persists to, or {@code null} when it has no durable file - i.e. an in-memory
     * Config (from {@link #inMemory()} or a bare {@code new Config()}) that is backed only by memory. A
     * caller that only needs the path can use {@link #getPath()} and skip the {@link File} allocation.
     */
    @Nullable
    public File getFile() {
        final Path p = getPath();
        return p == null ? null : p.toFile();
    }

    /**
     * The path this Config persists to, or {@code null} when it has no durable file (an in-memory Config).
     * This is the file the lifecycle ({@link #save()}/{@link #reload()}) reads and writes.
     */
    @Nullable
    public Path getPath() {
        return backStore == null ? null : backStore.path();
    }

    /** The path separator for the dynamic API: always {@link DPath#SEP}. */
    public char pathSeparator() {
        return DPath.SEP;
    }

    /** Join a base path with a sub-path using this config's separator (used by {@link ConfigSection}). */
    public String concat(final String base, final String sub) {
        return DPath.join(base, sub);
    }

    // ==================== navigation ====================

    /** Read navigation: returns null when absent; a stored NullNode is returned as-is, because a
     *  present-but-null value is distinct from an absent path. Bracket segments ({@code list[0]},
     *  {@code list[-1]}) force array-element semantics; a dotted numeric segment ({@code list.0}) stays
     *  ambiguous and is resolved against the live node's type, as before. */
    private JsonNode resolve(final String path) {
        JsonNode cur = root;
        for (final DPath.Seg seg : DPath.parse(path)) {
            if (seg.index) {
                if (!(cur instanceof ArrayNode)) {
                    return null; // [n] only addresses an array element
                }
                final int idx = arrayIndex((ArrayNode) cur, seg.indexValue);
                if (idx < 0) {
                    return null; // out of bounds (incl. a too-large negative)
                }
                cur = cur.get(idx);
            } else if (cur instanceof ObjectNode) {
                if (!((ObjectNode) cur).has(seg.key)) {
                    return null;
                }
                cur = cur.get(seg.key);
            } else if (cur instanceof ArrayNode && DPath.isIndex(seg.key)) {
                cur = cur.get(Integer.parseInt(seg.key));
                if (cur == null) {
                    return null;
                }
            } else {
                return null;
            }
            if (cur != null && cur.isMissingNode()) {
                return null;
            }
        }
        return cur;
    }

    /** Normalize a possibly-negative bracket index against {@code arr}'s size; -1 when out of bounds. */
    private static int arrayIndex(final ArrayNode arr, final int raw) {
        final int idx = raw < 0 ? raw + arr.size() : raw;
        return (idx < 0 || idx >= arr.size()) ? -1 : idx;
    }

    // ==================== set / remove ====================

    /**
     * Stores {@code value} at {@code path} ({@code ""}/{@code null} = the root), <em>replacing</em> whatever
     * is there. A native value (scalar, {@code Map}, list, {@link JsonNode}, or a scalar-serializing type
     * like UUID/java.time) is a raw replace. An arbitrary POJO (one that serializes to an object) is
     * projected annotation-aware through the bound codec — {@code @Comment} seeded, {@code @Section}
     * relocated, {@code @Key}/enum-by-name honored — and then <em>overrides</em> the subtree at {@code path}:
     * keys the POJO does not declare (and their comments) do NOT survive. Sibling keys outside {@code path}
     * are untouched. To instead MERGE a POJO into the existing subtree (unknown keys survive, the tree wins),
     * use {@link #mergeValue(String, Object)}. A {@code null} value removes the path.
     */
    public void setValue(final String path, final Object value) {
        writeValue(path, value, false);
    }

    /**
     * Stores {@code value} at {@code path} ({@code ""}/{@code null} = the root), MERGING a POJO into the
     * existing subtree: the POJO is the source of truth only for the keys it declares, and unknown sibling
     * keys under {@code path} — plus the comment overlay and key order — survive (the tree wins).
     * {@code @Comment} is seeded (never written over a user edit), {@code @Section} relocated, and
     * {@code @Key}/enum-by-name honored. This is the explicit merge counterpart of
     * {@link #setValue(String, Object)} (which replaces the subtree). A native value (scalar, {@code Map},
     * list, {@link JsonNode}) has nothing to merge, so it behaves exactly like {@code setValue} (a raw
     * replace). A {@code null} value removes the path.
     */
    public void mergeValue(final String path, final Object value) {
        writeValue(path, value, true);
    }

    /**
     * The write entry for {@link #setValue}/{@link #mergeValue}. A top-level {@code Collection}/{@code Map}
     * of hook-bearing elements bypasses the binder (the keyindex/compact/raw paths serialize through the
     * mapper), so its elements' lifecycle hooks would never fire — bracket the write with nested
     * {@code PRE_SAVE}/{@code POST_SAVE}, each element sectioned at its real sub-path ({@code base[i]} /
     * {@code base.<idValue>} / {@code base.<key>}). The compact-element form has no sub-path, so it only
     * warns. A genuine POJO routes through the binder ({@link #overrideEntity}/{@link #mergeEntity}), which
     * fires its own graph — so this bracketing is for the container-at-top case only.
     */
    private void writeValue(final String path, final Object value, final boolean merge) {
        if (codec != null && value instanceof Collection) {
            final Collection<?> coll = (Collection<?>) value;
            if (!coll.isEmpty() && LifecycleGraphWalker.anyMayHaveHooks(coll)) {
                if (isCompactElementCollection(coll)) {
                    LifecycleGraphWalker.warnCompactHooks(firstElementType(coll));
                    writeValueImpl(path, value, merge);
                    return;
                }
                final String base = path == null ? "" : path;
                final boolean keyed = isKeyIndexedCollection(coll);
                LifecycleGraphWalker.fireCollectionElements(this, base, coll, keyed,
                        LifecycleInvoker.Phase.PRE_SAVE, Collections.<LoadIssue>emptyList());
                writeValueImpl(path, value, merge);
                LifecycleGraphWalker.fireCollectionElements(this, base, coll, keyed,
                        LifecycleInvoker.Phase.POST_SAVE, Collections.<LoadIssue>emptyList());
                return;
            }
        } else if (codec != null && value instanceof Map) {
            final Map<?, ?> map = (Map<?, ?>) value;
            if (!map.isEmpty() && LifecycleGraphWalker.anyMayHaveHooks(map.values())) {
                final String base = path == null ? "" : path;
                LifecycleGraphWalker.fireMapValues(this, base, map,
                        LifecycleInvoker.Phase.PRE_SAVE, Collections.<LoadIssue>emptyList());
                writeValueImpl(path, value, merge);
                LifecycleGraphWalker.fireMapValues(this, base, map,
                        LifecycleInvoker.Phase.POST_SAVE, Collections.<LoadIssue>emptyList());
                return;
            }
        }
        writeValueImpl(path, value, merge);
    }

    /** The first non-null element's runtime class, or null for an all-null collection. */
    private static Class<?> firstElementType(final Collection<?> collection) {
        for (final Object e : collection) {
            if (e != null) {
                return e.getClass();
            }
        }
        return null;
    }

    /**
     * The shared write body for {@link #setValue}/{@link #mergeValue}: identical for every value except a
     * genuine POJO, where {@code merge} chooses between a merge into the existing subtree and an override
     * that replaces it.
     */
    private void writeValueImpl(final String path, final Object value, final boolean merge) {
        final boolean root = DPath.isRoot(path);
        if (value == null) {
            if (root) {
                setRoot(null);
            } else {
                removeValue(path);
            }
            return;
        }
        // A collection whose element type carries @KeyIndex serializes key-major (a section keyed by the id),
        // not as a plain array. It needs the codec mapper for the element bodies; @KeyIndex values must be
        // unique (KeyIndexer throws otherwise). An empty collection has no element to classify, so it falls
        // through and stores a plain empty array. The indexed node is a preformed node (not an entity), so it
        // takes the raw-replace path below regardless of merge/override.
        if (codec != null && value instanceof Collection && isKeyIndexedCollection((Collection<?>) value)) {
            writeValue(path, KeyIndexer.toIndexed((Collection<?>) value, codec.getObjectMapper()), merge);
            return;
        }
        // A collection whose element type resolves to a compact element form serializes as a string-list (one
        // compact line per element), even though the SAME type stays rich as a solo value/field — the mapper
        // never sees the compact form. The string array is a preformed node, so it takes the raw-replace path.
        // The compact form is resolved per-codec, so a codec-less Config never takes this path.
        if (codec != null && value instanceof Collection && isCompactElementCollection((Collection<?>) value)) {
            writeValue(path, ElementStringList.toStringArray((Collection<?>) value, nodes,
                    codec.compactElementResolver()), merge);
            return;
        }
        final JsonNode node = coercion.toNode(value);
        if (node == null || node.isMissingNode()) {
            if (root) {
                setRoot(null);
            } else {
                removeValue(path);
            }
            return;
        }
        // A genuine POJO (serializes to an object, and is not a Map/JsonNode) routes through the binder;
        // everything native takes the raw replace path below.
        if (codec != null && node instanceof ObjectNode && isEntityValue(value)) {
            if (merge) {
                mergeEntity(root ? "" : path, value);
            } else {
                overrideEntity(root ? "" : path, value);
            }
            return;
        }
        if (root) {
            setRoot(value);
            return;
        }
        setLeafValue(path, node);
    }

    /** True when {@code value} is a genuine entity to merge — not a {@code Map}/{@link JsonNode}, which also
     *  serializes to an object but must be set raw (it is a preformed node, so there is no bean schema to merge
     *  against). */
    private static boolean isEntityValue(final Object value) {
        return !TypeFamily.isPreformedNodeOrMap(value);
    }

    /** True when {@code collection}'s element type carries {@code @KeyIndex} (classified from the first
     *  non-null element, assuming a homogeneous collection); an empty collection is not indexed. */
    private static boolean isKeyIndexedCollection(final Collection<?> collection) {
        for (final Object e : collection) {
            return e != null && KeyIndexer.isKeyIndexed(e.getClass());
        }
        return false;
    }

    /** True when {@code collection}'s element type resolves to a compact element form via this codec's resolver
     *  (classified from the first non-null element); an empty collection is a plain array. Only reached with a
     *  non-null {@code codec}. */
    private boolean isCompactElementCollection(final Collection<?> collection) {
        for (final Object e : collection) {
            return e != null && codec.compactElementResolver().resolve(e.getClass()) != null;
        }
        return false;
    }

    /**
     * Set the leaf at {@code path}, handling both the dotted and the {@code [n]} bracket grammar through one
     * walk. Missing intermediate keys are minted as objects, but an array is never grown — a bracket index
     * must address an element that already exists (negative counts from the end). Out-of-bounds, a bracket
     * index into a non-array, or a (dotted) key segment landing on an array intermediate all throw rather
     * than silently grow or clobber a container.
     */
    private void setLeafValue(final String path, final JsonNode node) {
        final List<DPath.Seg> segs = DPath.parse(path);
        final StringBuilder parentDotted = new StringBuilder();
        final JsonNode parent = walkToParent(segs, true, parentDotted, path);
        final DPath.Seg last = segs.get(segs.size() - 1);
        if (last.index) {
            final ArrayNode arr = requireArray(parent, path);
            final int idx = arrayIndex(arr, last.indexValue);
            if (idx < 0) {
                throw new IllegalArgumentException("array index out of bounds (no growth) for '" + path + "'");
            }
            dropReplacedComments(arr.get(idx), parentDotted, String.valueOf(idx));
            arr.set(idx, node);
        } else if (parent instanceof ArrayNode && DPath.isIndex(last.key)) {
            final ArrayNode arr = (ArrayNode) parent;
            final int idx = Integer.parseInt(last.key);
            if (idx < 0 || idx >= arr.size()) {
                throw new IllegalArgumentException("array index out of bounds (no growth) for '" + path + "'");
            }
            dropReplacedComments(arr.get(idx), parentDotted, last.key);
            arr.set(idx, node);
        } else if (parent instanceof ObjectNode) {
            final ObjectNode obj = (ObjectNode) parent;
            dropReplacedComments(obj.get(last.key), parentDotted, last.key);
            obj.set(last.key, node);
        } else {
            throw new IllegalArgumentException("cannot set '" + path + "': its parent is not a container");
        }
        dirty = true;
    }

    /** Drop a replaced container's comment subtree, keyed by the parent's dotted path plus this leaf. */
    private void dropReplacedComments(final JsonNode existing, final StringBuilder parentDotted, final String leaf) {
        if (existing != null && existing.isContainerNode()) {
            final String escLeaf = DPath.escapeSegment(leaf);
            final String dotted = parentDotted.length() == 0 ? escLeaf : parentDotted + String.valueOf(DPath.SEP) + escLeaf;
            comments.removeSubtree(dotted);
        }
    }

    private static ArrayNode requireArray(final JsonNode parent, final String path) {
        if (!(parent instanceof ArrayNode)) {
            throw new IllegalArgumentException("bracket index into a non-array for '" + path + "'");
        }
        return (ArrayNode) parent;
    }

    /**
     * Walk every segment but the last, returning the container that holds the leaf. Descends into
     * existing arrays for bracket/numeric segments and, when {@code create}, mints intermediate objects
     * for missing keys; {@code dotted} is filled with the parent's normalized dotted path (for comment
     * keying). A non-create walk returns null when a segment is absent or type-incompatible.
     */
    private JsonNode walkToParent(final List<DPath.Seg> segs, final boolean create,
                                  final StringBuilder dotted, final String path) {
        JsonNode cur = root;
        for (int i = 0; i < segs.size() - 1; i++) {
            final DPath.Seg seg = segs.get(i);
            if (seg.index) {
                final ArrayNode arr = (cur instanceof ArrayNode) ? (ArrayNode) cur : null;
                final int idx = arr == null ? -1 : arrayIndex(arr, seg.indexValue);
                if (idx < 0) {
                    if (create) {
                        throw new IllegalArgumentException("cannot descend through '" + path + "'");
                    }
                    return null;
                }
                appendDotted(dotted, String.valueOf(idx));
                cur = arr.get(idx);
            } else if (cur instanceof ArrayNode && DPath.isIndex(seg.key)) {
                final JsonNode el = cur.get(Integer.parseInt(seg.key));
                if (el == null) {
                    if (create) {
                        throw new IllegalArgumentException("cannot descend through '" + path + "'");
                    }
                    return null;
                }
                appendDotted(dotted, seg.key);
                cur = el;
            } else if (cur instanceof ObjectNode) {
                final ObjectNode obj = (ObjectNode) cur;
                appendDotted(dotted, seg.key);
                final JsonNode next = obj.get(seg.key);
                if (next instanceof ObjectNode || next instanceof ArrayNode) {
                    cur = next;
                } else if (create) {
                    final ObjectNode created = nodes.objectNode();
                    obj.set(seg.key, created);
                    comments.removeSubtree(dotted.toString()); // a replaced scalar's comments no longer apply
                    cur = created;
                } else {
                    return null;
                }
            } else {
                if (create) {
                    throw new IllegalArgumentException("cannot descend into a scalar at '" + path + "'");
                }
                return null;
            }
        }
        return cur;
    }

    private void appendDotted(final StringBuilder dotted, final String seg) {
        if (dotted.length() > 0) {
            dotted.append(DPath.SEP);
        }
        dotted.append(DPath.escapeSegment(seg));
    }

    public void setValue(final String path, final Object value, final String comment) {
        setValue(path, value);
        if (value != null && comment != null) {
            setComment(path, comment);
        }
    }

    /** As {@link #mergeValue(String, Object)}, also setting (overwriting) the block comment at {@code path}. */
    public void mergeValue(final String path, final Object value, final String comment) {
        mergeValue(path, value);
        if (value != null && comment != null) {
            setComment(path, comment);
        }
    }

    private void setRoot(final Object value) {
        dirty = true;
        if (value == null) {
            root.removeAll();
            comments.removeSubtree("");
            return;
        }
        final JsonNode node = coercion.toNode(value);
        if (!(node instanceof ObjectNode)) {
            throw new IllegalArgumentException("the root must be an object");
        }
        root.removeAll();
        comments.removeSubtree("");
        root.setAll((ObjectNode) node);
    }

    public boolean removeValue(final String path) {
        if (DPath.isRoot(path)) {
            root.removeAll();
            comments.removeSubtree("");
            return true;
        }
        return removeLeafValue(path);
    }

    /** Remove the element/key at {@code path}, handling both the dotted and the {@code [n]} bracket grammar
     *  through one walk. */
    private boolean removeLeafValue(final String path) {
        final List<DPath.Seg> segs = DPath.parse(path);
        final StringBuilder parentDotted = new StringBuilder();
        final JsonNode parent = walkToParent(segs, false, parentDotted, path);
        if (parent == null) {
            return false;
        }
        final DPath.Seg last = segs.get(segs.size() - 1);
        boolean removed = false;
        String leafDotted = null;
        if (last.index) {
            if (parent instanceof ArrayNode) {
                final ArrayNode arr = (ArrayNode) parent;
                final int idx = arrayIndex(arr, last.indexValue);
                if (idx >= 0) {
                    arr.remove(idx);
                    leafDotted = String.valueOf(idx);
                    removed = true;
                }
            }
        } else if (parent instanceof ObjectNode) {
            removed = ((ObjectNode) parent).remove(last.key) != null;
            leafDotted = last.key;
        } else if (parent instanceof ArrayNode && DPath.isIndex(last.key)) {
            final int idx = Integer.parseInt(last.key);
            if (idx >= 0 && idx < ((ArrayNode) parent).size()) {
                ((ArrayNode) parent).remove(idx);
                leafDotted = last.key;
                removed = true;
            }
        }
        if (removed) {
            final String dotted = parentDotted.length() == 0
                    ? leafDotted : parentDotted + String.valueOf(DPath.SEP) + leafDotted;
            comments.removeSubtree(dotted);
            dirty = true;
        }
        return removed;
    }

    // ==================== get ====================

    public Object getValue(final String path) {
        final JsonNode n = resolve(path);
        return n == null ? null : coercion.unwrap(n);
    }

    /**
     * Read the value at {@code path} bound to {@code type} — a scalar ({@code Integer}, {@code String},
     * ...) or a POJO. Lenient: a value that cannot be bound yields the type's default (often null), and an
     * absent path yields null/the default. Runs {@code @PostLoad} for a POJO. Uses the lifecycle codec.
     *
     * @throws IllegalStateException if this config was not opened with a codec (e.g. {@code new Config()})
     */
    public <T> T getValue(final String path, final Class<T> type) {
        return getValue(path, type, requireCodec());
    }

    /** As {@link #getValue(String, Class)}, binding through an explicitly supplied {@code codec}. */
    public <T> T getValue(final String path, final Class<T> type, final Codec codec) {
        return bind(type, codec).read(path);
    }

    /** Bind the subtree at {@code path} ONTO {@code target} (overwriting only where it carries a value) and
     *  return {@code target} — the in-place counterpart to {@link #getValue(String, Class)}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> T getValueInto(final String path, final T target) {
        final EntityBinder binder = bind(target.getClass(), requireCodec());
        return (T) binder.readInto(path, target);
    }

    /** The raw Jackson node at {@code path}, or null when absent — the binding layer's read entry point. */
    public JsonNode getNode(final String path) {
        return resolve(path);
    }

    public String getString(final String path) {
        return getString(path, null);
    }

    public String getString(final String path, final String def) {
        final String s = coercion.asString(resolve(path));
        return s != null ? s : def;
    }

    public int getInt(final String path) {
        return getInt(path, 0);
    }

    public int getInt(final String path, final int def) {
        final Integer v = coercion.asInt(resolve(path));
        return v != null ? v : def;
    }

    public long getLong(final String path) {
        return getLong(path, 0L);
    }

    public long getLong(final String path, final long def) {
        final Long v = coercion.asLong(resolve(path));
        return v != null ? v : def;
    }

    public double getDouble(final String path) {
        return getDouble(path, 0.0);
    }

    public double getDouble(final String path, final double def) {
        final Double v = coercion.asDouble(resolve(path));
        return v != null ? v : def;
    }

    public boolean getBoolean(final String path) {
        return getBoolean(path, false);
    }

    public boolean getBoolean(final String path, final boolean def) {
        final Boolean v = coercion.asBoolean(resolve(path));
        return v != null ? v : def;
    }

    public List<String> getStringList(final String path) {
        final List<String> l = coercion.asStringList(resolve(path));
        return l != null ? l : new ArrayList<String>();
    }

    public List<String> getStringList(final String path, final List<String> def) {
        if (!contains(path)) {
            return def;
        }
        final List<String> l = coercion.asStringList(resolve(path));
        return l != null ? l : def;
    }

    /**
     * The list at {@code path} bound to typed {@code elementType} instances — a scalar element type
     * ({@code Integer}, {@code String}, ...) or a POJO. Empty (never null) when the path is absent or not
     * a list; lenient — an element that cannot be bound to {@code elementType} is skipped. Uses the
     * lifecycle codec.
     *
     * @throws IllegalStateException if this config was not opened with a codec (e.g. {@code new Config()})
     */
    public <T> List<T> getList(final String path, final Class<T> elementType) {
        return getList(path, elementType, requireCodec());
    }

    /** As {@link #getList(String, Class)}, binding through an explicitly supplied {@code codec}. */
    public <T> List<T> getList(final String path, final Class<T> elementType, final Codec codec) {
        return readList(path, elementType, codec, null);
    }

    /**
     * As {@link #getList(String, Class)}, returning the list together with any {@link LoadIssue}s collected —
     * the issues a bare {@link #getList} discards. The relevant case is a {@code @KeyIndex}-indexed read where
     * an element's body id disagreed with its section key (the section key wins, the disagreement is recorded).
     */
    public <T> BindResult<List<T>> getListResult(final String path, final Class<T> elementType) {
        return getListResult(path, elementType, requireCodec());
    }

    /** As {@link #getListResult(String, Class)}, binding through an explicitly supplied {@code codec}. */
    public <T> BindResult<List<T>> getListResult(final String path, final Class<T> elementType, final Codec codec) {
        final List<LoadIssue> issues = new ArrayList<>();
        final List<T> out = readList(path, elementType, codec, issues);
        return new BindResult<>(out, issues);
    }


    /**
     * Read the list at {@code path}. When {@code elementType} carries {@code @KeyIndex} AND the stored node is
     * an object, it is read as a key-major section (the section key is the id authority); otherwise it is read
     * as a plain array. Lenient: an unbindable array element is skipped, and an indexed element whose body id
     * disagrees with its section key is reconciled to the key. {@code issues}, when non-null, collects the
     * indexed-read reconciliations.
     */
    private <T> List<T> readList(final String path, final Class<T> elementType, final Codec codec,
                                final List<LoadIssue> issues) {
        final JsonNode node = getNode(path);
        final ObjectMapper mapper = codec.getObjectMapper();
        if (node instanceof ObjectNode && KeyIndexer.isKeyIndexed(elementType)) {
            final List<LoadIssue> sink = issues != null ? issues : new ArrayList<LoadIssue>();
            final List<T> keyed = KeyIndexer.fromIndexed(node, elementType, mapper, sink);
            firePostLoadElements(path, keyed, true, sink);
            return keyed;
        }
        // A type with a compact element form is read tolerantly: a textual element via its compact codec, an
        // object element via the normal rich bind (so a list mixing both shapes still reads).
        @SuppressWarnings("unchecked")
        final CompactElementCodec<T> compact =
                (CompactElementCodec<T>) codec.compactElementResolver().resolve(elementType);
        if (compact != null) {
            LifecycleGraphWalker.warnCompactHooks(elementType); // a compact element has no sub-path for its hooks
            return ElementStringList.fromArray(node, elementType, mapper, compact);
        }
        final List<T> out = new ArrayList<>();
        if (!(node instanceof ArrayNode)) {
            return out;
        }
        for (final JsonNode element : node) {
            try {
                out.add(mapper.convertValue(element, elementType));
            } catch (final IllegalArgumentException badElement) {
                // lenient: skip an element that cannot be bound to elementType
            }
        }
        firePostLoadElements(path, out, false, issues != null ? issues : Collections.<LoadIssue>emptyList());
        return out;
    }

    /**
     * Fire nested {@code @PostLoad} for the elements of a list just read (each at {@code path[i]} for a plain
     * list, or {@code path.<idValue>} for a {@code @KeyIndex} list), plus each element's descendants — the
     * hooks the per-element {@code mapper.convertValue} bypassed. A no-op unless some element actually carries
     * hooks, so a scalar list pays nothing.
     */
    private void firePostLoadElements(final String path, final List<?> elements, final boolean keyIndexed,
                                      final List<LoadIssue> issues) {
        if (codec != null && !elements.isEmpty() && LifecycleGraphWalker.anyMayHaveHooks(elements)) {
            LifecycleGraphWalker.fireCollectionElements(this, path == null ? "" : path, elements, keyIndexed,
                    LifecycleInvoker.Phase.POST_LOAD, issues);
        }
    }

    /** The UUID at {@code path}, or null when absent or malformed — tolerant like the numeric getters
     *  (a bad value never throws; pass a default overload to substitute one). */
    public UUID getUUID(final String path) {
        return coercion.asUuid(resolve(path));
    }

    public UUID getUUID(final String path, final UUID def) {
        final UUID u = coercion.asUuid(resolve(path));
        return u != null ? u : def;
    }

    // ==================== keys / containment / sections ====================

    public boolean contains(final String path) {
        return resolve(path) != null;
    }

    public Set<String> getKeys() {
        return keysOf(root);
    }

    public Set<String> getKeys(final String path) {
        return keysOf(resolve(path));
    }

    public Set<String> getKeys(final String path, final boolean deep) {
        if (!deep) {
            return getKeys(path);
        }
        final JsonNode n = resolve(path);
        final Set<String> out = new LinkedHashSet<>();
        if (n instanceof ObjectNode) {
            collectDeep((ObjectNode) n, "", out);
        }
        return out;
    }

    private Set<String> keysOf(final JsonNode n) {
        if (!(n instanceof ObjectNode)) {
            return Collections.emptySet();
        }
        final Set<String> out = new LinkedHashSet<>();
        ((ObjectNode) n).fieldNames().forEachRemaining(out::add);
        return out;
    }

    private void collectDeep(final ObjectNode node, final String prefix, final Set<String> out) {
        final Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            final Map.Entry<String, JsonNode> e = it.next();
            final String p = DPath.joinSegment(prefix, e.getKey());
            out.add(p);
            if (e.getValue() instanceof ObjectNode) {
                collectDeep((ObjectNode) e.getValue(), p, out);
            }
        }
    }

    /** A section view scoped at {@code path}; always non-null (a cursor, even when the path is absent).
     *  To test existence, use {@link #contains(String)} instead. */
    public ConfigSection getConfigSection(final String path) {
        return new ConfigSection(this, path);
    }

    public Set<ConfigSection> getKeysSections() {
        return getKeysSections("");
    }

    public Set<ConfigSection> getKeysSections(final String path) {
        final Set<ConfigSection> out = new LinkedHashSet<>();
        for (final String k : getKeys(path)) {
            out.add(new ConfigSection(this, DPath.joinSegment(path, k)));
        }
        return out;
    }

    // ==================== comment seam (seed-on-absent + pass-through) ====================

    /** Set the comment, overwriting any existing one. */
    public void setComment(final String path, final String comment) {
        comments.setComment(path, comment, CommentType.BLOCK);
        dirty = true;
    }

    public void setComment(final String path, final String comment, final CommentType type) {
        comments.setComment(path, comment, type);
        dirty = true;
    }

    /** Set the comment only when {@code path} has none yet; an existing (e.g. user-edited) comment wins. */
    public void setDefaultComment(final String path, final String comment) {
        setDefaultComment(path, comment, CommentType.BLOCK);
    }

    public void setDefaultComment(final String path, final String comment, final CommentType type) {
        if (comment != null && comments.getComment(path, type) == null) {
            comments.setComment(path, comment, type);
            dirty = true;
        }
    }

    public String getComment(final String path) {
        return comments.getComment(path, CommentType.BLOCK);
    }

    public String getComment(final String path, final CommentType type) {
        return comments.getComment(path, type);
    }

    // ==================== header / footer ====================

    /**
     * Sets the file header — the prefix-less comment block emitted above the first key — overwriting any
     * existing one. Each argument may contain {@code \n} (split into lines), so an ASCII-art text block can
     * be passed as a single argument; no arguments clears it. An empty interior line round-trips as a bare
     * comment line ({@code #}/{@code //}) — the only truly blank line in the file is the separator before the
     * first key, so the header never swallows the first key's own comment. Held in memory but never written
     * on a NONE-fidelity codec (e.g. JSON). Returns {@code this} for chaining.
     */
    public Config setHeader(final String... lines) {
        comments.setHeader(flattenCommentLines(lines));
        dirty = true;
        return this;
    }

    /** Sets the header only when none exists yet (a user-written header wins) — the set-if-absent
     *  counterpart of {@link #setHeader}; flags a seeded default like {@link #setDefaultComment}. */
    public Config setDefaultHeader(final String... lines) {
        if (getHeader().isEmpty()) {
            final List<String> flat = flattenCommentLines(lines);
            if (!flat.isEmpty()) {
                comments.setHeader(flat);
                dirty = true;
                newSeededDefaults = true;
            }
        }
        return this;
    }

    /** The file header lines (prefix-less); empty (never null) when absent. */
    public List<String> getHeader() {
        return comments.getHeader();
    }

    /** Clears the file header. Returns {@code this}. */
    public Config clearHeader() {
        comments.setHeader(null);
        dirty = true;
        return this;
    }

    /** As {@link #setHeader}, for the footer comment block emitted below the last key. */
    public Config setFooter(final String... lines) {
        comments.setFooter(flattenCommentLines(lines));
        dirty = true;
        return this;
    }

    /** As {@link #setDefaultHeader}, for the footer. */
    public Config setDefaultFooter(final String... lines) {
        if (getFooter().isEmpty()) {
            final List<String> flat = flattenCommentLines(lines);
            if (!flat.isEmpty()) {
                comments.setFooter(flat);
                dirty = true;
                newSeededDefaults = true;
            }
        }
        return this;
    }

    /** The file footer lines (prefix-less); empty (never null) when absent. */
    public List<String> getFooter() {
        return comments.getFooter();
    }

    /** Clears the file footer. Returns {@code this}. */
    public Config clearFooter() {
        comments.setFooter(null);
        dirty = true;
        return this;
    }

    /** Flatten header/footer varargs into prefix-less lines: each argument is split on newlines (CR/LF
     *  normalized), so a multi-line text block becomes one line per row and an empty line is kept as a blank
     *  line; a null argument is skipped. No arguments yields an empty list (which clears the block). */
    private static List<String> flattenCommentLines(final String... lines) {
        if (lines == null || lines.length == 0) {
            return Collections.emptyList();
        }
        final List<String> out = new ArrayList<>();
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final String normalized = line.replace("\r\n", "\n").replace('\r', '\n');
            for (final String part : normalized.split("\n", -1)) {
                out.add(part);
            }
        }
        return out;
    }

    /**
     * Move a key's data and its full comment subtree (the key's own block/side comment and blank-line
     * spacing AND those of every descendant) from {@code oldPath} to {@code newPath}. The moved comments
     * are written as authoritative, so they are preserved and not re-seeded. This is the explicit hook for
     * a config migration; reconciliation never infers a rename by itself.
     *
     * <p>Safe to run unconditionally on every startup. The returned {@link MigrationResult} tells a real
     * move ({@link MigrationResult#MOVED}) from a benign re-run ({@link MigrationResult#ALREADY_MIGRATED},
     * the source already moved) and from a suspicious no-op ({@link MigrationResult#SOURCE_ABSENT}, neither
     * side exists — often a typo). When both sides exist the source overwrites the destination and the
     * result is {@code MOVED}.
     */
    public MigrationResult migrateKey(final String oldPath, final String newPath) {
        if (DPath.isRoot(oldPath) || DPath.isRoot(newPath)) {
            return MigrationResult.INVALID_ROOT;
        }
        if (oldPath.equals(newPath)) {
            return MigrationResult.SAME_PATH;
        }
        if (!contains(oldPath)) {
            // Source gone: the destination holding the data means the migration ran before (benign);
            // an empty destination means there was nothing to migrate (often a typo in oldPath).
            return contains(newPath) ? MigrationResult.ALREADY_MIGRATED : MigrationResult.SOURCE_ABSENT;
        }
        final JsonNode node = resolve(oldPath);
        // Detach the comment subtree BEFORE the data move so neither wipe (removeValue on the source,
        // setValue on the destination) can destroy it; re-attach it under the new path afterwards.
        final CommentTree.Snapshot movedComments = comments.detachSubtree(oldPath);
        removeValue(oldPath);
        setValue(newPath, node); // a raw JsonNode passes through coercion unchanged (source overwrites target)
        comments.attachSubtree(newPath, movedComments);
        return MigrationResult.MOVED;
    }

    // ==================== key ordering (pin) ====================

    /** Pins {@code path} to the {@link KeyOrder.Zone#FIRST} zone — it floats above its siblings on save. */
    public Config pinFirst(final String path) {
        return pin(path, KeyOrder.Zone.FIRST);
    }

    /** Pins {@code path} to the {@link KeyOrder.Zone#LAST} zone — it sinks below its siblings on save, so it
     *  stays at the bottom even as new keys are seeded later (the "keep Debug last" case). */
    public Config pinLast(final String path) {
        return pin(path, KeyOrder.Zone.LAST);
    }

    /**
     * Pins {@code path} to an emit {@link KeyOrder.Zone}: {@code FIRST} floats it above its siblings,
     * {@code LAST} sinks it below them, {@code NORMAL} (or {@code null}) clears the pin (like {@link #unpin}).
     *
     * <p>A pin is an in-memory ordering POLICY, not file data: it is re-asserted on every save and survives a
     * {@link #reload()}, but nothing is written to mark it — so re-assert it on a fresh process (e.g. at
     * startup), the same way {@code @Comment}/{@code getOrSetValueIfAbsent} are re-run. A pin on a key that
     * does not exist yet stays latent until the key appears. Effect is per-key at its own level: {@code path}
     * is a full dotted path, so {@code pinLast("a.b")} sinks {@code b} within {@code a} only.
     *
     * <p>Best-effort per codec: YAML and JSONC honor it fully; TOML honors it within the scalar group and
     * within the table group but never moves a scalar past a table (TOML emits bare keys before
     * {@code [table]}s); JSON has no structure emitter, so its plain output keeps live-tree order. Returns
     * {@code this} for chaining.
     */
    public Config pin(final String path, final KeyOrder.Zone zone) {
        if (DPath.isRoot(path)) {
            throw new IllegalArgumentException("cannot pin the root");
        }
        if (zone == null || zone == KeyOrder.Zone.NORMAL) {
            return unpin(path);
        }
        pins.put(path, zone);
        dirty = true;
        return this;
    }

    /** Clears any pin on {@code path}, so it returns to the captured/append order. Returns {@code this}. */
    public Config unpin(final String path) {
        if (DPath.isRoot(path)) {
            throw new IllegalArgumentException("cannot unpin the root");
        }
        if (pins.remove(path) != null) {
            dirty = true;
        }
        return this;
    }

    /** The captured order with the live pin directives re-applied — the order actually handed to the emitter.
     *  Pins live apart from the captured snapshot so a reload (which recaptures it) keeps them. */
    private KeyOrder effectiveKeyOrder() {
        if (pins.isEmpty()) {
            return fileKeyOrder;
        }
        KeyOrder order = fileKeyOrder;
        for (final Map.Entry<String, KeyOrder.Zone> e : pins.entrySet()) {
            final String path = e.getKey();
            order = order.withPin(DPath.parent(path), DPath.leaf(path), e.getValue());
        }
        return order;
    }

    // ==================== getOrSetValueIfAbsent (the seeding engine) ====================

    /**
     * Read the value at {@code path}, or — when absent — seed it with {@code def} and return {@code def}.
     * {@code def} may be a scalar (coerced to its runtime type) or a POJO (the existing subtree is bound to
     * the default's type, so a POJO default reads back typed rather than as a raw node).
     */
    public <D> D getOrSetValueIfAbsent(final String path, final D def) {
        if (!contains(path)) {
            setValue(path, def);
            newSeededDefaults = true;
            return def;
        }
        if (def != null && !isScalarDefault(def) && codec != null) {
            @SuppressWarnings("unchecked")
            final D bound = (D) getValue(path, def.getClass());
            return bound != null ? bound : def;
        }
        final D coerced = coerceLikeDefault(resolve(path), def);
        return coerced != null ? coerced : def;
    }

    public <D> D getOrSetValueIfAbsent(final String path, final D def, final String comment) {
        return getOrSetValueIfAbsent(path, def, comment, CommentType.BLOCK);
    }

    public <D> D getOrSetValueIfAbsent(final String path, final D def, final String comment,
                                       final CommentType type) {
        final D value = getOrSetValueIfAbsent(path, def);
        seedCommentIfAbsent(path, comment, type);
        return value;
    }

    /**
     * The list at {@code path}, or — when absent — seeded with {@code def}. When present, the existing list
     * is bound to the default's element type (inferred from the first default element), so a list of POJOs
     * reads back typed.
     */
    @SuppressWarnings("unchecked")
    public <D> List<D> getOrSetValueIfAbsent(final String path, final List<D> def) {
        if (!contains(path)) {
            setValue(path, def);
            newSeededDefaults = true;
            return def;
        }
        if (def != null && !def.isEmpty() && codec != null) {
            return getList(path, (Class<D>) def.get(0).getClass());
        }
        return (List<D>) (List<?>) getList(path, Object.class);
    }

    public <D> List<D> getOrSetValueIfAbsent(final String path, final List<D> def, final String comment) {
        return getOrSetValueIfAbsent(path, def, comment, CommentType.BLOCK);
    }

    public <D> List<D> getOrSetValueIfAbsent(final String path, final List<D> def, final String comment,
                                             final CommentType type) {
        final List<D> value = getOrSetValueIfAbsent(path, def);
        seedCommentIfAbsent(path, comment, type);
        return value;
    }

    /**
     * Field-level get-or-seed for a whole entity, MERGING: ensures every field of {@code def} exists in the
     * tree at {@code path} (seeding the ones the file lacks — so a field added to the POJO appears in an old
     * file), loads the file's values onto {@code def}, and returns {@code def}. The file wins for fields it
     * already has. This is the merge counterpart of {@link #getOrSetValueIfAbsent(String, Object)}, which
     * seeds all-or-nothing (only when the whole path is absent, and never fills in a field a partial subtree
     * lacks).
     */
    public <D> D getOrMergeValue(final String path, final D def) {
        seedEntityFieldwise(path, def);
        return def;
    }

    /** As {@link #getOrMergeValue(String, Object)}, but the completed values land on {@code target}
     *  (a separate instance) while {@code def} supplies the defaults to seed. */
    public <D> D getOrMergeValue(final String path, final D def, final D target) {
        seedEntityFieldwise(path, def);
        return getValueInto(path, target);
    }

    /** @deprecated renamed to {@link #getOrMergeValue(String, Object)} for symmetry with
     *  {@link #mergeValue}; this alias delegates and may be removed in a future major. */
    @Deprecated
    public <D> D getOrSetValueIfAbsentInto(final String path, final D def) {
        return getOrMergeValue(path, def);
    }

    /** @deprecated renamed to {@link #getOrMergeValue(String, Object, Object)}; this alias delegates. */
    @Deprecated
    public <D> D getOrSetValueIfAbsentInto(final String path, final D def, final D target) {
        return getOrMergeValue(path, def, target);
    }

    /** Read the file onto {@code def} (file wins where present), then write {@code def} back so the fields
     *  the file lacked are seeded — the engine behind {@link #getOrSetValueIfAbsentInto}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void seedEntityFieldwise(final String path, final Object def) {
        final EntityBinder binder = bind(def.getClass(), requireCodec());
        binder.readInto(path, def);
        binder.write(path, def);
        newSeededDefaults = true;
    }

    /** True for a default whose existing value is read back by scalar coercion rather than entity binding. */
    private static boolean isScalarDefault(final Object def) {
        return TypeFamily.isNativeScalar(def);
    }

    private void seedCommentIfAbsent(final String path, final String comment) {
        seedCommentIfAbsent(path, comment, CommentType.BLOCK);
    }

    private void seedCommentIfAbsent(final String path, final String comment, final CommentType type) {
        // A default comment: written only when the path has none yet, so a user-edited comment wins.
        if (comment != null && comments.getComment(path, type) == null) {
            comments.setComment(path, comment, type);
            newSeededDefaults = true;
            dirty = true;
        }
    }

    /** A {@code void} alias for {@link #getOrSetValueIfAbsent(String, Object)} — same seed-if-absent
     *  behavior, for callers that only want to ensure a default exists and do not need the returned value. */
    public void setValueIfAbsent(final String path, final Object value) {
        getOrSetValueIfAbsent(path, value);
    }

    public void setValueIfAbsent(final String path, final Object value, final String comment) {
        getOrSetValueIfAbsent(path, value, comment);
    }

    public void setValueIfAbsent(final String path, final Object value, final String comment,
                                 final CommentType type) {
        getOrSetValueIfAbsent(path, value, comment, type);
    }

    /** Re-tag an already-stored scalar to the default's runtime type, so a value held as a long reads
     *  back as the Integer the caller's default implies (avoiding a ClassCastException). */
    @SuppressWarnings("unchecked")
    private <D> D coerceLikeDefault(final JsonNode node, final D def) {
        if (def instanceof String) {
            return (D) coercion.asString(node);
        }
        if (def instanceof Integer) {
            return (D) coercion.asInt(node);
        }
        if (def instanceof Long) {
            return (D) coercion.asLong(node);
        }
        if (def instanceof Double) {
            return (D) coercion.asDouble(node);
        }
        if (def instanceof Float) {
            final Double d = coercion.asDouble(node);
            return d == null ? null : (D) Float.valueOf(d.floatValue());
        }
        if (def instanceof Boolean) {
            return (D) coercion.asBoolean(node);
        }
        return (D) coercion.unwrap(node);
    }

    // ==================== typed entity binding (derived view) ====================

    /** A typed binder over this config's tree, using {@code codec}'s mapper, with default options. */
    public <T> EntityBinder<T> bind(final Class<T> type, final Codec codec) {
        return bind(type, codec, BindOptions.defaults());
    }

    public <T> EntityBinder<T> bind(final Class<T> type, final Codec codec, final BindOptions options) {
        final JavaType jt = codec.getObjectMapper().constructType(type);
        return new EntityBinder<>(this, jt, codec, options);
    }

    /** As {@link #bind(Class, Codec)} using the lifecycle codec this config was opened with. */
    public <T> EntityBinder<T> bind(final Class<T> type) {
        return bind(type, requireCodec());
    }

    /** As {@link #bind(Class, Codec, BindOptions)} using the lifecycle codec. */
    public <T> EntityBinder<T> bind(final Class<T> type, final BindOptions options) {
        return bind(type, requireCodec(), options);
    }

    /** Convenience: bind the whole tree to a fresh {@code T} (runs {@code @PostLoad}). */
    public <T> T loadAs(final Class<T> type, final Codec codec) {
        return bind(type, codec).read("");
    }

    /**
     * As {@link #loadAs}, but returns the value together with the {@link LoadIssue}s collected for the bind
     * — the issues a bare {@link #loadAs} discards (it drops the binder).
     */
    public <T> BindResult<T> loadAsResult(final Class<T> type, final Codec codec) {
        return bind(type, codec).readResult("");
    }

    private Codec requireCodec() {
        if (codec == null) {
            throw new IllegalStateException(
                    "this Config has no codec; open it via Config.open(...) or pass a codec explicitly");
        }
        return codec;
    }

    /** Merge a POJO into the tree at {@code path} via the binder (annotation-aware), using the lifecycle
     *  codec — the engine behind a POJO {@link #mergeValue(String, Object)}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mergeEntity(final String path, final Object pojo) {
        final EntityBinder binder = bind(pojo.getClass(), codec);
        binder.write(path, pojo);
        dirty = true; // the binder mutated the tree/comments directly, so flag a pending save
    }

    /**
     * Replace the subtree at {@code path} with {@code pojo}'s annotation-aware projection — the override
     * counterpart of {@link #mergeEntity} and the engine behind a POJO {@link #setValue(String, Object)}.
     * The existing node is cleared first (its keys AND their comments), so keys the POJO does not declare do
     * not survive; the projection is then merged into the now-empty node (seeding {@code @Comment},
     * relocating {@code @Section}). Sibling keys outside {@code path} are untouched. Clearing the root's
     * comment subtree also drops the file header/footer — the same as a native root replace — so a class-level
     * {@code @Comment} re-seeds the header on the merge that follows.
     */
    private void overrideEntity(final String path, final Object pojo) {
        final JsonNode existing = getNode(path);
        if (existing instanceof ObjectNode) {
            ((ObjectNode) existing).removeAll();               // clear in place -> the key keeps its parent position
            comments.removeSubtree(path == null ? "" : path);  // the replaced subtree's comments no longer apply
        }
        mergeEntity(path, pojo);
    }

    // ==================== save-defaults bookkeeping ====================

    /**
     * Whether any default (a value or comment the file lacked) was seeded since the last
     * {@link #clearNewSeededDefaults()}. This is a finer signal than the lifecycle's dirty flag:
     * {@link #saveIfDirty()} persists on ANY unsaved mutation, while this means specifically "the file's
     * shape evolved — keys/comments that did not exist were added". Use it to tell completing an old file
     * with new defaults apart from an ordinary value edit; use {@link #saveIfDirty()} when you only care
     * that something changed.
     */
    public boolean hasNewSeededDefaults() {
        return newSeededDefaults;
    }

    public void clearNewSeededDefaults() {
        newSeededDefaults = false;
    }

    // ==================== in-memory + codec selection ====================

    /**
     * An in-memory Config bound to {@link InMemoryCodec}: the full typed/POJO API works ({@code setValue}/
     * {@code mergeValue}, {@code getValue(path, type)}, {@code @Key}/{@code @Section}/{@code @Comment}, enum-by-name,
     * {@code java.time}, {@code Optional}) but there is no file or text format, so it cannot be persisted —
     * {@link #save()} throws. To persist, open a real file with {@link #open} instead. This differs from
     * {@code new Config()}, which has no codec at all and accepts only native (scalar/{@code Map}/list/
     * {@link JsonNode}) values.
     */
    public static Config inMemory() {
        return inMemory(InMemoryCodec.INSTANCE);
    }

    /** As {@link #inMemory()}, with a caller-supplied codec (e.g. an {@link InMemoryCodec} over a custom
     *  mapper); the codec supplies the binding mapper and there is no back-store. */
    public static Config inMemory(final Codec codec) {
        final Config cfg = new Config();
        cfg.codec = codec;
        cfg.bindCoercionTo(codec);
        return cfg;
    }

    /**
     * Switches the codec this Config binds and saves with, rebinding the POJO coercion to the new codec's
     * mapper. Returns {@code this} for chaining. The back-store path (if any) is unchanged, so persisting a
     * format that disagrees with the file name is the caller's responsibility; for a one-off, prefer
     * {@link #save(Codec)}. Switching from a comment-bearing codec to one without comment fidelity (e.g.
     * JSON) drops the comment overlay on the next save.
     */
    public Config changeCodec(final Codec codec) {
        if (codec == null) {
            throw new IllegalArgumentException("codec cannot be null");
        }
        lock.lock();
        try {
            this.codec = codec;
            bindCoercionTo(codec);
        } finally {
            lock.unlock();
        }
        return this;
    }

    // ==================== lifecycle (backStore-backed) ====================

    /**
     * Opens a Config over a file, choosing the codec from the file's extension via
     * {@link CodecRegistry#defaults()}. Throws a {@link CodecException} when the extension is missing or
     * not registered — it never guesses a format.
     */
    public static Config open(final String path) {
        return open(Paths.get(path));
    }

    /** As {@link #open(String)}, from a {@link File} (its path is used). */
    public static Config open(final File file) {
        return open(file.toPath());
    }

    /** As {@link #open(String)}, choosing the codec from {@code path}'s file-name extension. */
    public static Config open(final Path path) {
        return open(path, codecForPath(path));
    }

    /**
     * Opens a Config over a file. If the file parses, its contents become the tree; if it exists but
     * cannot be parsed, it is backed up to {@code .bak} and an empty tree is used (the file is not
     * overwritten); if it is absent, an empty tree is used and the first {@link #save()} creates it.
     * Never throws on a malformed file — a corrupt config must not block startup.
     */
    public static Config open(final Path path, final Codec codec) {
        return open(path, codec, BackStore.Durability.OS_CACHE);
    }

    /**
     * As {@link #open(Path, Codec)}, but choosing how durably each {@link #save()} must land:
     * {@link BackStore.Durability#OS_CACHE} (the default) returns once the atomic rename is visible, while
     * {@link BackStore.Durability#FSYNC} forces the bytes to the storage device first (slower, crash-safe).
     */
    public static Config open(final Path path, final Codec codec,
                              final BackStore.Durability durability) {
        final Config cfg = new Config();
        cfg.backStore = new AtomicFileBackStore(path, durability);
        cfg.codec = codec;
        cfg.bindCoercionTo(codec);
        cfg.loadInternal(true);
        return cfg;
    }

    /** Resolves the codec for a file path from its extension, failing fast on an unknown/missing one. */
    private static Codec codecForPath(final Path path) {
        final Path name = path.getFileName();
        if (name == null) {
            throw new CodecException("path has no file name to resolve a codec: " + path);
        }
        return CodecRegistry.defaults().forFile(name.toString());
    }

    /**
     * Wires the dynamic API's arbitrary-POJO escape to the codec's {@link ObjectMapper}, so
     * {@code setValue(path, pojo)} can store any Jackson-serializable object (honoring the binding
     * annotations). Every codec is Jackson-backed (exposes a mapper), so this is unconditional.
     */
    private void bindCoercionTo(final Codec codec) {
        coercion.setPojoToNode(codec.getObjectMapper()::valueToTree);
    }

    private void requireBackStore() {
        if (backStore == null || codec == null) {
            throw new IllegalStateException("this Config has no backStore; build it with Config.open(path, codec)");
        }
    }

    private void loadInternal(final boolean backupOnParseFail) {
        byte[] bytes;
        try {
            bytes = backStore.readBytes();
        } catch (final IOException e) {
            throw new ConfigIOException("failed to read " + backStore.describe(), e);
        }
        if (bytes == null || bytes.length == 0) {
            applyLoaded(nodes.objectNode(), new CommentTree(), KeyOrder.empty());
            lastLoadStatus = (bytes == null) ? LoadStatus.ABSENT : LoadStatus.EMPTY;
        } else {
            try {
                decodeInto(bytes);
                lastLoadStatus = LoadStatus.OK;
            } catch (final CodecException parseFail) {
                if (backupOnParseFail) {
                    safeBackup();
                }
                applyLoaded(nodes.objectNode(), new CommentTree(), KeyOrder.empty());
                lastLoadStatus = LoadStatus.PARSE_FAILED_BACKED_UP;
            }
        }
        loaded = backStore.fingerprint();
    }

    private void decodeInto(final byte[] bytes) {
        final String text = new String(bytes, backStore.charset());
        final JsonNode tree = codec.readTree(text);
        final ObjectNode newRoot = (tree instanceof ObjectNode) ? (ObjectNode) tree : nodes.objectNode();
        final CommentTree newComments;
        final KeyOrder newOrder;
        if (codec instanceof CommentAware) {
            final CommentAware.CommentLoad load = ((CommentAware) codec).readComments(text);
            newComments = load.comments;
            newOrder = load.keyOrder;
        } else {
            newComments = new CommentTree();
            newOrder = KeyOrder.capture(newRoot);
        }
        applyLoaded(newRoot, newComments, newOrder);
    }

    private void applyLoaded(final ObjectNode newRoot, final CommentTree newComments, final KeyOrder newOrder) {
        this.root = newRoot;
        this.comments = newComments;
        this.fileKeyOrder = newOrder;
        this.dirty = false;
    }

    /** Encode the tree (with comments + key order) through {@code codec}, using {@code charset} for the
     *  text→bytes step. The comment emitter is used only when the codec round-trips comments.
     *
     *  <p>The tree and comment overlay are snapshotted (a private deep copy) before the emit — and this runs
     *  under the per-Config {@code lock} held by every {@code save(...)} path. The emit then iterates the
     *  copy, so a concurrent UNLOCKED mutation (a {@code setValue} or a raw {@code getRoot()} edit on another
     *  thread) can no longer corrupt the document mid-write. It is a partial guard: a {@code Config} is still
     *  a single-writer handle (the copy itself can race a concurrent mutation), but the long emit window —
     *  where the {@code ConcurrentModificationException} used to surface — is closed. {@link KeyOrder} is an
     *  immutable load-time snapshot, so it is shared, not copied. */
    private byte[] encodeWith(final Codec codec, final Charset charset) {
        final ObjectNode treeSnapshot = root.deepCopy();
        final CommentTree commentsSnapshot = comments.copy();
        final String text;
        if (codec instanceof CommentAware && codec.commentFidelity() != CommentFidelity.NONE) {
            text = ((CommentAware) codec).writeWithComments(treeSnapshot, commentsSnapshot, effectiveKeyOrder());
        } else {
            text = codec.writeTreePlain(treeSnapshot);
        }
        return text.getBytes(charset);
    }

    private void safeBackup() {
        try {
            backStore.backupUnparseable();
        } catch (final IOException ignored) {
            // best-effort: a failed backup must not block the load
        }
    }

    /** Encodes the tree (with comments + key order) and writes it atomically; serialized per-Config. */
    public void save() {
        requireBackStore();
        doSave(codec, backStore.charset());
    }

    /**
     * One-shot save in a DIFFERENT format: encodes the tree through {@code codec} (its comment fidelity and
     * charset) and writes it atomically to the same file, without changing the codec this Config keeps. The
     * file extension is not changed, so emitting a format the name does not imply is the caller's call — a
     * later extension-inferred {@link #open} would pick the wrong codec. Requires a back-store (from
     * {@link #open}).
     */
    public void save(final Codec codec) {
        requireBackStore();
        doSave(codec, codec.charset());
    }

    /** The shared save body: under the per-Config lock, encode through {@code codec}/{@code charset} (the
     *  encode stays inside the lock so concurrent saves serialize against the latest tree) and write
     *  atomically, refreshing the loaded fingerprint and any watcher snapshot. */
    private void doSave(final Codec codec, final Charset charset) {
        lock.lock();
        try {
            final Fingerprint written = backStore.writeAtomic(encodeWith(codec, charset));
            loaded = written;
            if (watcher != null) {
                watcher.refreshSnapshot(written);
            }
            dirty = false;
        } catch (final IOException e) {
            throw new ConfigIOException("failed to save " + backStore.describe(), e);
        } finally {
            lock.unlock();
        }
    }

    /** Saves only if the tree was mutated since the last load/save. */
    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    /** Fire-and-forget save on the shared async executor (virtual threads on Java 21+, a daemon pool below). */
    public CompletableFuture<Void> saveAsync() {
        return CompletableFuture.runAsync(this::save, ConfigExecutors.get());
    }

    /**
     * Re-reads the file into the tree. A missing file keeps the current tree ({@link LoadStatus#ABSENT});
     * a file that exists but cannot be parsed keeps the current tree without backing up or overwriting
     * ({@link LoadStatus#PARSE_FAILED_KEPT}, a recorded divergence). Only a clean parse replaces the tree.
     */
    public void reload() {
        requireBackStore();
        lock.lock();
        try {
            if (!backStore.exists()) {
                lastLoadStatus = LoadStatus.ABSENT;
                return;
            }
            final byte[] bytes = backStore.readBytes();
            try {
                decodeInto(bytes);
                loaded = backStore.fingerprint();
                lastLoadStatus = LoadStatus.OK;
            } catch (final CodecException parseFail) {
                lastLoadStatus = LoadStatus.PARSE_FAILED_KEPT;
            }
        } catch (final IOException e) {
            throw new ConfigIOException("failed to reload " + backStore.describe(), e);
        } finally {
            lock.unlock();
        }
    }

    public long getLastModified() {
        return loaded.mtime;
    }

    /** True if the durable file's fingerprint differs from what was last loaded/saved. */
    public boolean hasBeenModified() {
        return backStore != null && !backStore.fingerprint().equals(loaded);
    }

    public LoadStatus lastLoadStatus() {
        return lastLoadStatus;
    }

    /** True when in-memory state may differ from the file because a reload kept stale data. */
    public boolean isDivergedFromDisk() {
        return lastLoadStatus == LoadStatus.PARSE_FAILED_KEPT;
    }

    /** Enables auto-reload: the file is polled on a daemon thread and the tree refreshed on change. */
    public Config withAutoReload(final Duration pollInterval) {
        return withAutoReload(pollInterval, false);
    }

    /**
     * As {@link #withAutoReload(Duration)}, but {@code detectInPlaceEdits} makes the watcher also catch a
     * same-size edit landing within one coarse mtime tick (it hashes content each poll instead of only
     * stat-ing it — a full read per poll, hence opt-in).
     */
    public Config withAutoReload(final Duration pollInterval, final boolean detectInPlaceEdits) {
        requireBackStore();
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("auto-reload poll interval must be positive");
        }
        close();
        this.watcher = backStore.watch(pollInterval, () -> {
            reload();
            final Runnable cb = this.onReload;
            if (cb != null) {
                cb.run();
            }
        }, detectInPlaceEdits);
        watcher.start();
        return this;
    }

    public Config onReload(final Runnable callback) {
        this.onReload = callback;
        return this;
    }

    public void stopAutoReload() {
        close();
    }

    /** Stops the watcher, if any. Idempotent. */
    @Override
    public void close() {
        final Watcher w = this.watcher;
        if (w != null) {
            w.close();
            this.watcher = null;
        }
    }

    @Override
    public String toString() {
        return root.toString();
    }
}
