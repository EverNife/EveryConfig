package br.com.finalcraft.finalconfig.config;

import br.com.finalcraft.finalconfig.backend.AtomicFileBackend;
import br.com.finalcraft.finalconfig.backend.Backend;
import br.com.finalcraft.finalconfig.backend.ConfigExecutor;
import br.com.finalcraft.finalconfig.binding.BindOptions;
import br.com.finalcraft.finalconfig.binding.EntityBinder;
import br.com.finalcraft.finalconfig.binding.IdIndexer;
import br.com.finalcraft.finalconfig.binding.LoadIssue;
import br.com.finalcraft.finalconfig.codec.Codec;
import br.com.finalcraft.finalconfig.codec.CommentAware;
import br.com.finalcraft.finalconfig.codec.CommentFidelity;
import br.com.finalcraft.finalconfig.codec.CodecException;
import br.com.finalcraft.finalconfig.codec.ObjectMapperAware;
import br.com.finalcraft.finalconfig.config.section.ConfigSection;
import br.com.finalcraft.finalconfig.core.KeyOrder;
import br.com.finalcraft.finalconfig.core.coerce.NodeCoercion;
import br.com.finalcraft.finalconfig.core.comment.CommentTree;
import br.com.finalcraft.finalconfig.core.comment.CommentType;
import br.com.finalcraft.finalconfig.core.tree.Path;
import br.com.finalcraft.finalconfig.core.tree.PathOptions;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
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
    private Backend backend;
    private Codec lifecycleCodec;
    private volatile Backend.Fingerprint loaded = Backend.Fingerprint.ABSENT;
    private volatile LoadStatus lastLoadStatus = LoadStatus.NEVER_LOADED;
    private boolean dirty = false;
    private Backend.Watcher watcher;
    private volatile Runnable onReload;

    /**
     * The paths that already existed in the tree when this config was loaded — the oracle for "is this
     * the first time the key is written?". A code-seeded comment fires only for a path NOT in this set,
     * so a comment the user deleted from the file stays deleted instead of being re-seeded every save.
     */
    private final Set<String> loadedPaths;

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
        this.loadedPaths = new LinkedHashSet<>();
        collectDeep(root, "", this.loadedPaths);
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
        return Path.join(base, sub, sep());
    }

    // ==================== navigation ====================

    /** Read navigation: returns null when absent; a stored NullNode is returned as-is, because a
     *  present-but-null value is distinct from an absent path. */
    private JsonNode resolve(final String path) {
        JsonNode cur = root;
        for (final String seg : Path.split(path, sep())) {
            if (cur instanceof ObjectNode) {
                if (!((ObjectNode) cur).has(seg)) {
                    return null;
                }
                cur = cur.get(seg);
            } else if (cur instanceof ArrayNode && Path.isIndex(seg)) {
                cur = cur.get(Integer.parseInt(seg));
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
        final String[] segs = Path.split(path, sep());
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
        if (Path.isRoot(path)) {
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
        final ParentAndKey pk = vivify(path);
        final JsonNode existing = pk.parent.get(pk.key);
        if (existing != null && existing.isContainerNode()) {
            comments.removeSubtree(path); // replacing a subtree drops its descendants' comments
        }
        pk.parent.set(pk.key, node);
        dirty = true;
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
        if (Path.isRoot(path)) {
            root.removeAll();
            comments.removeSubtree("");
            return true;
        }
        final JsonNode parent = resolve(Path.parent(path, sep()));
        final String leaf = Path.leaf(path, sep());
        boolean removed = false;
        if (parent instanceof ObjectNode) {
            removed = ((ObjectNode) parent).remove(leaf) != null;
        } else if (parent instanceof ArrayNode && Path.isIndex(leaf)) {
            ((ArrayNode) parent).remove(Integer.parseInt(leaf));
            removed = true;
        }
        if (removed) {
            comments.removeSubtree(path);
            dirty = true;
        }
        return removed;
    }

    /** Alias for {@link #removeValue(String)} (for callers that prefer a {@code clear} idiom). */
    public boolean clear(final String path) {
        return removeValue(path);
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

    /** True when {@code path} was present in the tree at load time — the "first write?" oracle for seeds. */
    public boolean isPersisted(final String path) {
        return loadedPaths.contains(path);
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
        return l != null ? l : new java.util.ArrayList<String>();
    }

    public List<String> getStringList(final String path, final List<String> def) {
        if (!contains(path)) {
            return def;
        }
        final List<String> l = coercion.asStringList(resolve(path));
        return l != null ? l : def;
    }

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

    /** Always returns a (possibly empty) section view (old {@code getConfigSection} contract). */
    public ConfigSection getConfigSection(final String path) {
        return new ConfigSection(this, path);
    }

    /** Returns a section view, or null when the path is absent or not an object (old contract). */
    public ConfigSection getConfigurationSection(final String path) {
        final JsonNode n = resolve(path);
        return (n instanceof ObjectNode) ? new ConfigSection(this, path) : null;
    }

    public Set<ConfigSection> getKeysSections() {
        return getKeysSections("");
    }

    public Set<ConfigSection> getKeysSections(final String path) {
        final Set<ConfigSection> out = new LinkedHashSet<>();
        for (final String k : getKeys(path)) {
            out.add(new ConfigSection(this, Path.join(path, k, sep())));
        }
        return out;
    }

    // ==================== comment seam (seed-on-absent + pass-through) ====================

    public void setComment(final String path, final String comment) {
        comments.setComment(path, comment, CommentType.BLOCK);
        dirty = true;
    }

    public void setComment(final String path, final String comment, final CommentType type) {
        comments.setComment(path, comment, type);
        dirty = true;
    }

    public String getComment(final String path) {
        return comments.getComment(path, CommentType.BLOCK);
    }

    public String getComment(final String path, final CommentType type) {
        return comments.getComment(path, type);
    }

    /**
     * Move a key's data and its own comment + spacing from {@code oldPath} to {@code newPath}, marking
     * the destination as already persisted so the moved comment is preserved (never re-seeded). This is
     * the explicit hook for a config migration; reconciliation never infers a rename by itself. The
     * moved node keeps its full data subtree; comments on descendant paths are not carried over.
     */
    public void migrateKey(final String oldPath, final String newPath) {
        if (Path.isRoot(oldPath) || Path.isRoot(newPath) || oldPath.equals(newPath) || !contains(oldPath)) {
            return;
        }
        final JsonNode node = resolve(oldPath);
        final String block = comments.getComment(oldPath, CommentType.BLOCK);
        final String side = comments.getComment(oldPath, CommentType.SIDE);
        final int blanks = comments.getBlankLinesBefore(oldPath);

        removeValue(oldPath);
        setValue(newPath, node); // a raw JsonNode passes through coercion unchanged
        if (block != null) {
            comments.setComment(newPath, block, CommentType.BLOCK);
        }
        if (side != null) {
            comments.setComment(newPath, side, CommentType.SIDE);
        }
        comments.setBlankLinesBefore(newPath, blanks);
        loadedPaths.remove(oldPath);
        loadedPaths.add(newPath);
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
        seedCommentIfUnpersisted(path, comment);
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
        seedCommentIfUnpersisted(path, comment);
        return value;
    }

    /**
     * Deposit a seeded block comment only when {@code path} is being written for the FIRST time (it was
     * not present at load and carries no authoritative comment). Re-running with a path the file already
     * had is a no-op, so a comment the user deleted is never resurrected.
     */
    private void seedCommentIfUnpersisted(final String path, final String comment) {
        if (comment != null && !loadedPaths.contains(path) && !comments.hasUserComment(path)) {
            comments.seedComment(path, comment);
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

    /** Merge a POJO into this config's tree (the in-memory write side; persistence is the backend's job). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void mergeFrom(final Object pojo, final Codec codec) {
        final EntityBinder binder = bind(pojo.getClass(), codec);
        binder.writeEntity(pojo);
    }

    /** Store a collection of {@code @Id}-bearing entities at {@code path} as a section keyed by their id. */
    public void writeIdCollection(final String path, final Collection<?> collection, final Codec codec) {
        final ObjectMapper mapper = ((ObjectMapperAware) codec).objectMapper();
        setValue(path, IdIndexer.toIndexed(collection, mapper));
    }

    /** Read an {@code @Id}-indexed section at {@code path} back into a list, restoring each id from its key. */
    public <T> List<T> readIdCollection(final String path, final Class<T> elementType, final Codec codec) {
        final ObjectMapper mapper = ((ObjectMapperAware) codec).objectMapper();
        final List<LoadIssue> issues = new ArrayList<>();
        final List<T> out = IdIndexer.fromIndexed(getNode(path), elementType, mapper, issues);
        this.lastIdCollectionIssues = Collections.unmodifiableList(issues);
        return out;
    }

    /** Issues recorded by the most recent {@link #readIdCollection} (e.g. a body id disagreeing with its key). */
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

    // ==================== lifecycle (backend-backed) ====================

    /**
     * Opens a Config over a file. If the file parses, its contents become the tree; if it exists but
     * cannot be parsed, it is backed up to {@code .bak} and an empty tree is used (the file is not
     * overwritten); if it is absent, an empty tree is used and the first {@link #save()} creates it.
     * Never throws on a malformed file — a corrupt config must not block startup.
     */
    public static Config open(final java.nio.file.Path path, final Codec codec) {
        final Config cfg = new Config();
        cfg.backend = new AtomicFileBackend(path);
        cfg.lifecycleCodec = codec;
        cfg.loadInternal(true);
        return cfg;
    }

    private void requireBackend() {
        if (backend == null || lifecycleCodec == null) {
            throw new IllegalStateException("this Config has no backend; build it with Config.open(path, codec)");
        }
    }

    private void loadInternal(final boolean backupOnParseFail) {
        byte[] bytes;
        try {
            bytes = backend.readBytes();
        } catch (final IOException e) {
            throw new ConfigIOException("failed to read " + backend.describe(), e);
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
        loaded = backend.fingerprint();
    }

    private void decodeInto(final byte[] bytes) {
        final String text = new String(bytes, backend.charset());
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
        this.loadedPaths.clear();
        collectDeep(this.root, "", this.loadedPaths);
        this.dirty = false;
    }

    private byte[] encode() {
        final String text;
        if (lifecycleCodec instanceof CommentAware && lifecycleCodec.commentFidelity() != CommentFidelity.NONE) {
            text = ((CommentAware) lifecycleCodec).writeWithComments(root, comments, fileKeyOrder);
        } else {
            text = lifecycleCodec.writeTreePlain(root);
        }
        return text.getBytes(backend.charset());
    }

    private void safeBackup() {
        try {
            backend.backupUnparseable();
        } catch (final IOException ignored) {
            // best-effort: a failed backup must not block the load
        }
    }

    /** Encodes the tree (with comments + key order) and writes it atomically; serialized per-Config. */
    public void save() {
        requireBackend();
        lock.lock();
        try {
            final Backend.Fingerprint written = backend.writeAtomic(encode());
            loaded = written;
            if (watcher != null) {
                watcher.refreshSnapshot(written);
            }
            dirty = false;
        } catch (final IOException e) {
            throw new ConfigIOException("failed to save " + backend.describe(), e);
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
        requireBackend();
        lock.lock();
        try {
            if (!backend.exists()) {
                lastLoadStatus = LoadStatus.ABSENT;
                return;
            }
            final byte[] bytes = backend.readBytes();
            try {
                decodeInto(bytes);
                loaded = backend.fingerprint();
                lastLoadStatus = LoadStatus.OK;
            } catch (final CodecException parseFail) {
                lastLoadStatus = LoadStatus.PARSE_FAILED_KEPT;
            }
        } catch (final IOException e) {
            throw new ConfigIOException("failed to reload " + backend.describe(), e);
        } finally {
            lock.unlock();
        }
    }

    public long getLastModified() {
        return loaded.mtime;
    }

    /** True if the durable file's fingerprint differs from what was last loaded/saved. */
    public boolean hasBeenModified() {
        return backend != null && !backend.fingerprint().equals(loaded);
    }

    public LoadStatus lastLoadStatus() {
        return lastLoadStatus;
    }

    /** True when in-memory state may differ from the file because a reload kept stale data. */
    public boolean isDivergedFromDisk() {
        return lastLoadStatus == LoadStatus.PARSE_FAILED_KEPT;
    }

    /** Enables auto-reload: the file is polled on a daemon thread and the tree refreshed on change. */
    public Config withAutoReload(final java.time.Duration pollInterval) {
        requireBackend();
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("auto-reload poll interval must be positive");
        }
        close();
        this.watcher = backend.watch(pollInterval, () -> {
            reload();
            final Runnable cb = this.onReload;
            if (cb != null) {
                cb.run();
            }
        });
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
        final Backend.Watcher w = this.watcher;
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
