package br.com.finalcraft.everyconfig.config;

import br.com.finalcraft.everyconfig.io.AtomicFileBackStore;
import br.com.finalcraft.everyconfig.io.BackStore;
import br.com.finalcraft.everyconfig.io.ConfigExecutor;
import br.com.finalcraft.everyconfig.binding.BindOptions;
import br.com.finalcraft.everyconfig.binding.BindResult;
import br.com.finalcraft.everyconfig.binding.EntityBinder;
import br.com.finalcraft.everyconfig.binding.merge.IdIndexer;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CommentAware;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.CodecException;
import br.com.finalcraft.everyconfig.codec.CodecRegistry;
import br.com.finalcraft.everyconfig.codec.ObjectMapperAware;
import br.com.finalcraft.everyconfig.config.section.ConfigSection;
import br.com.finalcraft.everyconfig.core.KeyOrder;
import br.com.finalcraft.everyconfig.core.coerce.NodeCoercion;
import br.com.finalcraft.everyconfig.core.comment.CommentTree;
import br.com.finalcraft.everyconfig.core.comment.CommentType;
import br.com.finalcraft.everyconfig.core.tree.DPath;
import br.com.finalcraft.everyconfig.core.tree.PathOptions;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
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
 * {@code getOrSetDefaultValue}). Typed entity binding and file I/O live elsewhere; {@code Config} is a
 * pure data+API object.
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
    private final PathOptions pathOptions;
    private final NodeCoercion coercion;

    // ---- lifecycle (null for an in-memory Config not opened over a file) ----
    private final ReentrantLock lock = new ReentrantLock(true);
    private BackStore backStore;
    private Codec lifecycleCodec;
    private volatile BackStore.Fingerprint loaded = BackStore.Fingerprint.ABSENT;
    private volatile LoadStatus lastLoadStatus = LoadStatus.NEVER_LOADED;
    private boolean dirty = false;
    private BackStore.Watcher watcher;
    private volatile Runnable onReload;

    private transient boolean newDefaultValueToSave = false;
    private List<LoadIssue> lastIdCollectionIssues = Collections.emptyList();

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
        this.pathOptions = PathOptions.DEFAULT;
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

    private char sep() {
        return pathOptions.separator();
    }

    public char pathSeparator() {
        return pathOptions.separator();
    }

    /** Join a base path with a sub-path using this config's separator (used by {@link ConfigSection}). */
    public String concat(final String base, final String sub) {
        return DPath.join(base, sub, sep());
    }

    // ==================== navigation ====================

    /** Read navigation: returns null when absent; a stored NullNode is returned as-is, because a
     *  present-but-null value is distinct from an absent path. Bracket segments ({@code list[0]},
     *  {@code list[-1]}) force array-element semantics; a dotted numeric segment ({@code list.0}) stays
     *  ambiguous and is resolved against the live node's type, as before. */
    private JsonNode resolve(final String path) {
        JsonNode cur = root;
        for (final DPath.Seg seg : DPath.parse(path, sep())) {
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

    private static final class ParentAndKey {
        final ObjectNode parent;
        final String key;

        ParentAndKey(final ObjectNode parent, final String key) {
            this.parent = parent;
            this.key = key;
        }
    }

    /** Write navigation: walks to the leaf's parent, creating intermediate objects as needed. Only
     *  ever mints ObjectNodes — a numeric segment never grows an array on the write path. */
    private ParentAndKey vivify(final String path) {
        final String[] segs = DPath.split(path, sep());
        if (segs.length == 0) {
            throw new IllegalArgumentException("cannot set the root path as a leaf value");
        }
        ObjectNode cur = root;
        final StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < segs.length - 1; i++) {
            if (prefix.length() > 0) {
                prefix.append(sep());
            }
            prefix.append(segs[i]);
            final JsonNode next = cur.get(segs[i]);
            if (next instanceof ObjectNode) {
                cur = (ObjectNode) next;
            } else {
                final ObjectNode created = nodes.objectNode();
                cur.set(segs[i], created);
                comments.removeSubtree(prefix.toString()); // replaced subtree's comments no longer apply
                cur = created;
            }
        }
        return new ParentAndKey(cur, segs[segs.length - 1]);
    }

    // ==================== set / remove ====================

    public void setValue(final String path, final Object value) {
        if (DPath.isRoot(path)) {
            setRoot(value);
            return;
        }
        if (value == null) {
            removeValue(path);
            return;
        }
        final JsonNode node = coercion.toNode(value);
        if (node == null || node.isMissingNode()) {
            removeValue(path);
            return;
        }
        if (DPath.hasBracket(path)) {
            setValueBracketed(path, node);
            return;
        }
        final ParentAndKey pk = vivify(path);
        final JsonNode existing = pk.parent.get(pk.key);
        if (existing != null && existing.isContainerNode()) {
            comments.removeSubtree(path); // replacing a subtree drops its descendants' comments
        }
        pk.parent.set(pk.key, node);
        dirty = true;
    }

    /**
     * Bracket-path write: set the leaf addressed by a path that uses {@code [n]} grammar. Intermediate
     * objects are minted as on the dotted path, but an array is never grown — a bracket index must
     * address an element that already exists (negative counts from the end). Out-of-bounds or a bracket
     * index into a non-array throws, mirroring the "{@code vivify} never grows an array" invariant.
     */
    private void setValueBracketed(final String path, final JsonNode node) {
        final List<DPath.Seg> segs = DPath.parse(path, sep());
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
            final String dotted = parentDotted.length() == 0 ? leaf : parentDotted + String.valueOf(sep()) + leaf;
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
            dotted.append(sep());
        }
        dotted.append(seg);
    }

    public void setValue(final String path, final Object value, final String comment) {
        setValue(path, value);
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
        if (DPath.hasBracket(path)) {
            return removeValueBracketed(path);
        }
        final JsonNode parent = resolve(DPath.parent(path, sep()));
        final String leaf = DPath.leaf(path, sep());
        boolean removed = false;
        if (parent instanceof ObjectNode) {
            removed = ((ObjectNode) parent).remove(leaf) != null;
        } else if (parent instanceof ArrayNode && DPath.isIndex(leaf)) {
            ((ArrayNode) parent).remove(Integer.parseInt(leaf));
            removed = true;
        }
        if (removed) {
            comments.removeSubtree(path);
            dirty = true;
        }
        return removed;
    }

    /** Bracket-path remove: drop the element/key addressed by a path that uses {@code [n]} grammar. */
    private boolean removeValueBracketed(final String path) {
        final List<DPath.Seg> segs = DPath.parse(path, sep());
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
                    ? leafDotted : parentDotted + String.valueOf(sep()) + leafDotted;
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
     * The list at {@code path} as plain Java values: scalars are unwrapped, but a nested object/array
     * element stays a raw {@link JsonNode}. It NEVER returns POJOs — for typed elements use
     * {@link #getLoadableList(String, Class)}. Null when the path is absent or not a list.
     */
    public List<Object> getList(final String path) {
        return coercion.asList(resolve(path));
    }

    public List<Object> getList(final String path, final List<Object> def) {
        final List<Object> l = coercion.asList(resolve(path));
        return l != null ? l : def;
    }

    public UUID getUUID(final String path) {
        final String s = getString(path);
        return s == null ? null : UUID.fromString(s);
    }

    public UUID getUUID(final String path, final UUID def) {
        try {
            final UUID u = getUUID(path);
            return u != null ? u : def;
        } catch (final IllegalArgumentException e) {
            return def;
        }
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
            final String p = prefix.isEmpty() ? e.getKey() : prefix + sep() + e.getKey();
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
            out.add(new ConfigSection(this, DPath.join(path, k, sep())));
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

    /**
     * Move a key's data and its full comment subtree (the key's own block/side comment and blank-line
     * spacing AND those of every descendant) from {@code oldPath} to {@code newPath}. The moved comments
     * are written as authoritative, so they are preserved and not re-seeded. This is the explicit hook for
     * a config migration; reconciliation never infers a rename by itself.
     */
    public void migrateKey(final String oldPath, final String newPath) {
        if (DPath.isRoot(oldPath) || DPath.isRoot(newPath) || oldPath.equals(newPath) || !contains(oldPath)) {
            return;
        }
        final JsonNode node = resolve(oldPath);
        // Detach the comment subtree BEFORE the data move so neither wipe (removeValue on the source,
        // setValue on the destination) can destroy it; re-attach it under the new path afterwards.
        final CommentTree.Snapshot movedComments = comments.detachSubtree(oldPath);
        removeValue(oldPath);
        setValue(newPath, node); // a raw JsonNode passes through coercion unchanged
        comments.attachSubtree(newPath, movedComments);
    }

    // ==================== getOrSetDefaultValue (the seeding engine) ====================

    public <D> D getOrSetDefaultValue(final String path, final D def) {
        if (!contains(path)) {
            setValue(path, def);
            newDefaultValueToSave = true;
            return def;
        }
        final D coerced = coerceLikeDefault(resolve(path), def);
        return coerced != null ? coerced : def;
    }

    public <D> D getOrSetDefaultValue(final String path, final D def, final String comment) {
        final D value = getOrSetDefaultValue(path, def);
        seedCommentIfAbsent(path, comment);
        return value;
    }

    @SuppressWarnings("unchecked")
    public <D> List<D> getOrSetDefaultValue(final String path, final List<D> def) {
        if (!contains(path)) {
            setValue(path, def);
            newDefaultValueToSave = true;
            return def;
        }
        return (List<D>) (List<?>) getList(path);
    }

    public <D> List<D> getOrSetDefaultValue(final String path, final List<D> def, final String comment) {
        final List<D> value = getOrSetDefaultValue(path, def);
        seedCommentIfAbsent(path, comment);
        return value;
    }

    private void seedCommentIfAbsent(final String path, final String comment) {
        // A default comment: written only when the path has none yet, so a user-edited comment wins.
        if (comment != null && comments.getComment(path, CommentType.BLOCK) == null) {
            comments.setComment(path, comment, CommentType.BLOCK);
            newDefaultValueToSave = true;
            dirty = true;
        }
    }

    public void setDefaultValue(final String path, final Object value) {
        getOrSetDefaultValue(path, value);
    }

    public void setDefaultValue(final String path, final Object value, final String comment) {
        getOrSetDefaultValue(path, value, comment);
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
        final JavaType jt = ((ObjectMapperAware) codec).objectMapper().constructType(type);
        return new EntityBinder<>(this, jt, codec, options);
    }

    /** Convenience: bind the whole tree to a fresh {@code T} (runs {@code @PostInject}). */
    public <T> T loadAs(final Class<T> type, final Codec codec) {
        return bind(type, codec).bind();
    }

    /**
     * As {@link #loadAs}, but returns the value together with the {@link LoadIssue}s collected for the bind
     * — the issues a bare {@link #loadAs} discards (it drops the binder).
     */
    public <T> BindResult<T> loadAsResult(final Class<T> type, final Codec codec) {
        return bind(type, codec).bindResult();
    }

    /**
     * Bind the subtree at {@code path} to a fresh {@code T} — the path-scoped {@link #loadAs}, using the
     * codec this config was opened with. An absent path binds the type's defaults; a root path
     * ({@code ""}/{@code null}) binds the whole tree. Runs {@code @PostInject}.
     *
     * @throws IllegalStateException if this config was not opened with a codec (e.g. {@code new Config()})
     */
    public <T> T getLoadable(final String path, final Class<T> type) {
        return getLoadable(path, type, requireLifecycleCodec());
    }

    /** As {@link #getLoadable(String, Class)}, binding through an explicitly supplied {@code codec}. */
    public <T> T getLoadable(final String path, final Class<T> type, final Codec codec) {
        return bind(type, codec).bindAt(path);
    }

    /**
     * Bind the list at {@code path} into typed {@code elementType} instances — the typed counterpart to
     * {@link #getList} (which yields scalars / raw {@link JsonNode}, never POJOs). An absent path or a
     * non-array value yields an empty list; an element that cannot be bound to {@code elementType} is
     * skipped (lenient, like the default bind). Uses the codec this config was opened with.
     *
     * @throws IllegalStateException if this config was not opened with a codec (e.g. {@code new Config()})
     */
    public <T> List<T> getLoadableList(final String path, final Class<T> elementType) {
        return getLoadableList(path, elementType, requireLifecycleCodec());
    }

    /** As {@link #getLoadableList(String, Class)}, binding through an explicitly supplied {@code codec}. */
    public <T> List<T> getLoadableList(final String path, final Class<T> elementType, final Codec codec) {
        final List<T> out = new ArrayList<>();
        final JsonNode node = getNode(path);
        if (!(node instanceof ArrayNode)) {
            return out; // absent or not a list -> empty
        }
        final ObjectMapper mapper = ((ObjectMapperAware) codec).objectMapper();
        for (final JsonNode element : node) {
            try {
                out.add(mapper.convertValue(element, elementType));
            } catch (final IllegalArgumentException badElement) {
                // lenient: skip an element that cannot be bound to elementType
            }
        }
        return out;
    }

    private Codec requireLifecycleCodec() {
        if (lifecycleCodec == null) {
            throw new IllegalStateException(
                    "this Config has no codec; open it via Config.open(...) or pass a codec explicitly");
        }
        return lifecycleCodec;
    }

    /** Merge a POJO into this config's tree (the in-memory write side; persistence is the backStore's job). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void mergeFrom(final Object pojo, final Codec codec) {
        final EntityBinder binder = bind(pojo.getClass(), codec);
        binder.writeEntity(pojo);
        dirty = true; // the binder mutated the tree/comments directly, so flag a pending save
    }

    /** Store a collection of {@code @Id}-bearing entities at {@code path} as a section keyed by their id. */
    public void writeIdCollection(final String path, final Collection<?> collection, final Codec codec) {
        final ObjectMapper mapper = ((ObjectMapperAware) codec).objectMapper();
        setValue(path, IdIndexer.toIndexed(collection, mapper));
    }

    /** Read an {@code @Id}-indexed section at {@code path} back into a list, restoring each id from its key. */
    public <T> List<T> readIdCollection(final String path, final Class<T> elementType, final Codec codec) {
        return readIdCollectionResult(path, elementType, codec).value();
    }

    /**
     * As {@link #readIdCollection}, but returns the list together with the {@link LoadIssue}s collected for
     * this read (e.g. a body id disagreeing with its key), sourced from the local list so a later read does
     * not race the result.
     */
    public <T> BindResult<List<T>> readIdCollectionResult(final String path, final Class<T> elementType,
                                                          final Codec codec) {
        final ObjectMapper mapper = ((ObjectMapperAware) codec).objectMapper();
        final List<LoadIssue> issues = new ArrayList<>();
        final List<T> out = IdIndexer.fromIndexed(getNode(path), elementType, mapper, issues);
        this.lastIdCollectionIssues = Collections.unmodifiableList(issues);
        return new BindResult<>(out, issues);
    }

    /** Issues recorded by the most recent {@link #readIdCollection} (e.g. a body id disagreeing with its
     *  key); {@link #readIdCollectionResult} returns the same issues alongside the list. */
    public List<LoadIssue> lastIdCollectionIssues() {
        return lastIdCollectionIssues;
    }

    // ==================== save-defaults bookkeeping ====================

    public boolean isNewDefaultValueToSave() {
        return newDefaultValueToSave;
    }

    public void clearNewDefaultValueToSave() {
        newDefaultValueToSave = false;
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
        cfg.lifecycleCodec = codec;
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
     * Wires the dynamic API's arbitrary-POJO escape to a codec's {@link ObjectMapper}, so
     * {@code setValue(path, pojo)} can store any Jackson-serializable object (honoring the binding
     * annotations) instead of throwing. Without a codec the escape stays unbound, since there is no
     * mapper to decide how an unknown type serializes.
     */
    private void bindCoercionTo(final Codec codec) {
        if (codec instanceof ObjectMapperAware) {
            final ObjectMapper mapper = ((ObjectMapperAware) codec).objectMapper();
            coercion.setPojoToNode(mapper::valueToTree);
        }
    }

    private void requireBackStore() {
        if (backStore == null || lifecycleCodec == null) {
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
        final JsonNode tree = lifecycleCodec.readTree(text);
        final ObjectNode newRoot = (tree instanceof ObjectNode) ? (ObjectNode) tree : nodes.objectNode();
        final CommentTree newComments;
        final KeyOrder newOrder;
        if (lifecycleCodec instanceof CommentAware) {
            final CommentAware.CommentLoad load = ((CommentAware) lifecycleCodec).readComments(text);
            newComments = load.comments;
            newOrder = load.keyOrder;
        } else {
            newComments = new CommentTree();
            newOrder = KeyOrder.capture(newRoot, sep());
        }
        applyLoaded(newRoot, newComments, newOrder);
    }

    private void applyLoaded(final ObjectNode newRoot, final CommentTree newComments, final KeyOrder newOrder) {
        this.root = newRoot;
        this.comments = newComments;
        this.fileKeyOrder = newOrder;
        this.dirty = false;
    }

    private byte[] encode() {
        final String text;
        if (lifecycleCodec instanceof CommentAware && lifecycleCodec.commentFidelity() != CommentFidelity.NONE) {
            text = ((CommentAware) lifecycleCodec).writeWithComments(root, comments, fileKeyOrder);
        } else {
            text = lifecycleCodec.writeTreePlain(root);
        }
        return text.getBytes(backStore.charset());
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
        lock.lock();
        try {
            final BackStore.Fingerprint written = backStore.writeAtomic(encode());
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

    /** Fire-and-forget save on the shared daemon executor. */
    public CompletableFuture<Void> saveAsync() {
        return CompletableFuture.runAsync(this::save, ConfigExecutor.shared());
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
        final BackStore.Watcher w = this.watcher;
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
