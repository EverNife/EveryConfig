package br.com.finalcraft.everyconfig.binding;
import br.com.finalcraft.everyconfig.binding.merge.PostInjectInvoker;
import br.com.finalcraft.everyconfig.binding.merge.SmartMerge;
import br.com.finalcraft.everyconfig.binding.schema.BindingNames;
import br.com.finalcraft.everyconfig.binding.schema.Schema;
import br.com.finalcraft.everyconfig.binding.schema.SchemaCache;

import br.com.finalcraft.everyconfig.annotation.Comment;
import br.com.finalcraft.everyconfig.annotation.CommentMode;
import br.com.finalcraft.everyconfig.annotation.Section;
import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.ObjectMapperAware;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.core.comment.CommentTree;
import br.com.finalcraft.everyconfig.core.comment.CommentType;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A typed view bound to one {@link Config}: it reads a POJO from the canonical tree and (write side,
 * elsewhere) merges a POJO back into it. Reading is a derived view — it never mutates the tree — and is
 * tolerant: unknown keys are ignored, missing keys keep the POJO's constructed defaults, and under the
 * lenient policy a single bad value is recorded as a {@link LoadIssue} rather than failing the load.
 */
public final class EntityBinder<T> {

    /** One schema cache per shared codec mapper, so a type is introspected once and reused. */
    private static final ConcurrentHashMap<ObjectMapper, SchemaCache> SCHEMA_CACHES = new ConcurrentHashMap<>();

    private final Config config;
    private final JavaType type;
    private final Codec codec;
    private final ObjectMapper mapper;
    private final BindOptions options;
    private final List<SectionField> sectionFields;

    private List<LoadIssue> lastIssues = Collections.emptyList();

    public EntityBinder(final Config config, final JavaType type, final Codec codec, final BindOptions options) {
        if (!(codec instanceof ObjectMapperAware)) {
            throw new BindException("codec " + codec.formatId() + " does not expose an ObjectMapper for binding");
        }
        this.config = config;
        this.type = type;
        this.codec = codec;
        this.mapper = ((ObjectMapperAware) codec).objectMapper();
        this.options = options != null ? options : BindOptions.defaults();
        this.sectionFields = collectSectionFields(type.getRawClass());
    }

    /** A field placed under a nested path by {@code @Section}, relative to its owning object. */
    private static final class SectionField {
        final String ownerPath;     // dotted path of the owning POJO within the root ("" at the root)
        final String flatKey;       // the key the mapper emits the field as, e.g. "max-size"
        final String sectionValue;  // the @Section path within the owner, e.g. "database.pool"

        SectionField(final String ownerPath, final String flatKey, final String sectionValue) {
            this.ownerPath = ownerPath;
            this.flatKey = flatKey;
            this.sectionValue = sectionValue;
        }

        /** Where the field's value lands within its owner, e.g. {@code "database.pool.max-size"}. */
        String relativePath() {
            return sectionValue + "." + flatKey;
        }
    }

    private static List<SectionField> collectSectionFields(final Class<?> raw) {
        final List<SectionField> out = new ArrayList<>();
        collectSectionFields(raw, "", new HashSet<Class<?>>(), out);
        return out;
    }

    /** Recursively gather {@code @Section} fields of {@code raw} and of its nested-POJO fields, each tagged
     *  with the dotted path of its owning object. {@code onPath} guards against self-referential cycles. */
    private static void collectSectionFields(final Class<?> raw, final String ownerPath,
                                             final Set<Class<?>> onPath, final List<SectionField> out) {
        if (raw == null || onPath.contains(raw)) {
            return;
        }
        onPath.add(raw);
        for (final Field f : BindingNames.allFields(raw)) {
            final String key = BindingNames.keyFor(f);
            final Section s = f.getAnnotation(Section.class);
            if (s != null && !s.value().isEmpty()) {
                out.add(new SectionField(ownerPath, key, s.value()));
                continue; // a sectioned field is not descended into (nested-within-section is out of scope)
            }
            if (isBindablePojo(f.getType())) {
                collectSectionFields(f.getType(), ownerPath.isEmpty() ? key : ownerPath + "." + key, onPath, out);
            }
        }
        onPath.remove(raw);
    }

    /** Whether a type should be walked for nested {@code @Section} fields (a user POJO, not a JDK/container). */
    private static boolean isBindablePojo(final Class<?> c) {
        if (c == null || c.isPrimitive() || c.isArray() || c.isEnum()) {
            return false;
        }
        if (Map.class.isAssignableFrom(c) || Collection.class.isAssignableFrom(c)) {
            return false;
        }
        final String n = c.getName();
        return !(n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk."));
    }

    ObjectMapper mapper() {
        return mapper;
    }

    JavaType type() {
        return type;
    }

    Config config() {
        return config;
    }

    BindOptions options() {
        return options;
    }

    // ---- READ: tree -> POJO --------------------------------------------

    /** Bind the whole current tree to a fresh instance (unknown keys ignored; missing keys keep defaults). */
    public T bind() {
        return doBind(constructDefault(), config.getRoot());
    }

    /** Bind into an existing instance, overwriting only where the tree carries a value. */
    public T bindInto(final T target) {
        return doBind(target, config.getRoot());
    }

    /** Bind the subtree at {@code path} to a fresh instance. */
    public T bindAt(final String path) {
        JsonNode node = config.getNode(path);
        if (node == null) {
            node = mapper.getNodeFactory().objectNode();
        }
        return doBind(constructDefault(), node);
    }

    /** Re-bind from the (possibly externally changed) tree and re-run lifecycle hooks. */
    public T reload() {
        return bind();
    }

    /**
     * As {@link #bind()}, but returns the value together with the {@link LoadIssue}s collected for this
     * call, so the issues travel with the value instead of having to be read afterward from the stateful
     * {@link #lastLoadIssues()} (which a later bind would overwrite).
     */
    public BindResult<T> bindResult() {
        final T v = bind();
        return new BindResult<>(v, lastIssues);
    }

    /** Issues from the most recent bind on this binder (empty when clean); {@link #bindResult()} returns the
     *  same issues alongside the value. */
    public List<LoadIssue> lastLoadIssues() {
        return lastIssues;
    }

    // ---- WRITE: POJO -> tree (merge, never replace) --------------------

    /**
     * Project {@code pojo} to a tree and merge it into the canonical tree at the root. Unknown file keys,
     * the comment overlay, and key order all survive; the POJO is the source of truth only for the keys
     * it declares. Comments from {@code @Comment} are seeded (never written over a user edit). This
     * mutates the in-memory tree only; persisting it is the back-store's job.
     */
    public void writeEntity(final T pojo) {
        mergeAndSeed(pojo, config.getRoot(), "");
    }

    /** Same as {@link #writeEntity}, scoped to the object at {@code path} (created if absent). */
    public void writeEntityAt(final String path, final T pojo) {
        final JsonNode existing = config.getNode(path);
        final ObjectNode target;
        if (existing instanceof ObjectNode) {
            target = (ObjectNode) existing;
        } else {
            target = mapper.getNodeFactory().objectNode();
            config.setValue(path, target);
        }
        mergeAndSeed(pojo, target, path);
    }

    private void mergeAndSeed(final T pojo, final ObjectNode target, final String basePath) {
        final JsonNode candidate = mapper.valueToTree(pojo);
        if (!(candidate instanceof ObjectNode)) {
            throw new BindException("entity " + pojo.getClass().getName() + " did not serialize to an object");
        }
        final ObjectNode candObj = (ObjectNode) candidate;
        // @Section fields are emitted flat by the mapper; move each to its nested location before the merge.
        // The schema is section-aware (the section spine is declared/owned), so the caller's obsolete policy
        // can stand — a relocated section is never mistaken for an obsolete key.
        if (!sectionFields.isEmpty()) {
            relocateForWrite(candObj);
        }
        SmartMerge.mergeInto(target, candObj, schema(), options, config.getCommentTree(), basePath,
                codec.commentFidelity() == CommentFidelity.LOSSLESS, config.pathSeparator());
        seedCommentsFromAnnotations(pojo.getClass(), basePath);
    }

    /** Surface each {@code @Section} field's nested value at its flat key (within its owner) for the mapper. */
    private JsonNode viewForRead(final JsonNode source) {
        if (!(source instanceof ObjectNode)) {
            return source;
        }
        final ObjectNode copy = (ObjectNode) source.deepCopy();
        for (final SectionField sf : sectionFields) {
            final ObjectNode owner = ownerNode(copy, sf.ownerPath);
            if (owner != null) {
                final JsonNode node = getAtPath(owner, sf.relativePath());
                if (node != null) {
                    owner.set(sf.flatKey, node);
                }
            }
        }
        return copy;
    }

    /** Move each {@code @Section} field from its flat key to its nested location within its owning object. */
    private void relocateForWrite(final ObjectNode candidate) {
        for (final SectionField sf : sectionFields) {
            final ObjectNode owner = ownerNode(candidate, sf.ownerPath);
            if (owner != null && owner.has(sf.flatKey)) {
                setAtPath(owner, sf.relativePath(), owner.remove(sf.flatKey));
            }
        }
    }

    /** The owning object at {@code ownerPath} ({@code ""} = the root node), or null if it is absent. */
    private static ObjectNode ownerNode(final ObjectNode root, final String ownerPath) {
        if (ownerPath.isEmpty()) {
            return root;
        }
        final JsonNode n = getAtPath(root, ownerPath);
        return n instanceof ObjectNode ? (ObjectNode) n : null;
    }

    private static JsonNode getAtPath(final ObjectNode root, final String dotted) {
        JsonNode cur = root;
        for (final String seg : dotted.split("\\.")) {
            if (!(cur instanceof ObjectNode)) {
                return null;
            }
            cur = cur.get(seg);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    private void setAtPath(final ObjectNode root, final String dotted, final JsonNode value) {
        final String[] segs = dotted.split("\\.");
        ObjectNode cur = root;
        for (int i = 0; i < segs.length - 1; i++) {
            final JsonNode next = cur.get(segs[i]);
            if (next instanceof ObjectNode) {
                cur = (ObjectNode) next;
            } else {
                final ObjectNode created = mapper.getNodeFactory().objectNode();
                cur.set(segs[i], created);
                cur = created;
            }
        }
        cur.set(segs[segs.length - 1], value);
    }

    private Schema schema() {
        return SCHEMA_CACHES.computeIfAbsent(mapper, SchemaCache::new).of(type);
    }

    /**
     * Seed comments declared by {@code @Comment} on the entity's fields (and the file header from a
     * class-level {@code @Comment}) into the comment overlay, writing each only where no comment exists
     * yet. A no-op when the codec cannot round-trip comments at all.
     */
    private void seedCommentsFromAnnotations(final Class<?> clazz, final String basePath) {
        if (codec.commentFidelity() == CommentFidelity.NONE) {
            return;
        }
        final CommentTree comments = config.getCommentTree();
        final Comment classComment = clazz.getAnnotation(Comment.class);
        if (classComment != null && basePath.isEmpty()
                && (classComment.mode() == CommentMode.OVERRIDE || comments.getHeader().isEmpty())) {
            comments.setHeader(Arrays.asList(classComment.value()));
        }
        for (final Field f : BindingNames.allFields(clazz)) {
            final Comment c = f.getAnnotation(Comment.class);
            if (c == null) {
                continue;
            }
            final String key = BindingNames.keyFor(f);
            final Section sec = f.getAnnotation(Section.class);
            final String fieldPath = (sec != null && !sec.value().isEmpty()) ? sec.value() + "." + key : key;
            final String path = basePath.isEmpty() ? fieldPath : basePath + config.pathSeparator() + fieldPath;
            final String text = String.join("\n", c.value());
            if (c.mode() == CommentMode.OVERRIDE) {
                comments.setComment(path, text, CommentType.BLOCK); // documentation stays current
            } else {
                comments.setDefaultComment(path, text, CommentType.BLOCK); // user-edited comment wins
            }
        }
    }

    private T doBind(final T base, final JsonNode rawSource) {
        final JsonNode source = sectionFields.isEmpty() ? rawSource : viewForRead(rawSource);
        final List<LoadIssue> issues = new ArrayList<>();
        T result;
        try {
            result = options.coercion() == BindOptions.Coercion.STRICT
                    ? bindOnce(base, source)
                    : bindLenient(base, source, issues);
        } catch (final BindException e) {
            throw e;
        } catch (final Exception e) {
            throw new BindException("failed to bind tree to " + type, e);
        }
        lastIssues = Collections.unmodifiableList(issues);
        if (result instanceof LoadIssueAware) {
            ((LoadIssueAware) result).setLoadIssues(lastIssues);
        }
        PostInjectInvoker.invoke(result, lastIssues);
        return result;
    }

    private T bindOnce(final T base, final JsonNode source) throws IOException {
        if (base != null) {
            return mapper.readerForUpdating(base).readValue(source);
        }
        return mapper.reader().forType(type).readValue(source);
    }

    /**
     * Tolerant bind: a value that cannot be coerced is removed from a working copy of the tree and
     * recorded, then the bind is retried — so the offending field keeps the default the base instance
     * already holds instead of being overwritten with a zero. A failure that cannot be isolated to a
     * single path stops the retry and the best-effort instance (good fields applied) is returned.
     */
    private T bindLenient(final T base, final JsonNode source, final List<LoadIssue> issues) throws IOException {
        final JsonNode working = source.deepCopy();
        final int maxAttempts = 256;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return bindOnce(base, working);
            } catch (final JsonMappingException e) {
                final List<JsonMappingException.Reference> path = e.getPath();
                issues.add(new LoadIssue(pathString(path), valueAt(working, path), type.getRawClass(),
                        shortMessage(e)));
                if (!removeAt(working, path)) {
                    break;
                }
            }
        }
        return base != null ? base : constructDefault();
    }

    private static String pathString(final List<JsonMappingException.Reference> path) {
        final StringBuilder sb = new StringBuilder();
        for (final JsonMappingException.Reference r : path) {
            if (r.getFieldName() != null) {
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(r.getFieldName());
            } else if (r.getIndex() >= 0) {
                sb.append('[').append(r.getIndex()).append(']');
            }
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    private static Object valueAt(final JsonNode root, final List<JsonMappingException.Reference> path) {
        JsonNode cur = root;
        for (final JsonMappingException.Reference r : path) {
            if (cur == null) {
                return null;
            }
            cur = r.getFieldName() != null ? cur.get(r.getFieldName()) : cur.get(r.getIndex());
        }
        if (cur == null) {
            return null;
        }
        return cur.isValueNode() ? cur.asText() : cur.toString();
    }

    /** Remove the node at {@code path} (a field) or null it out (an array element); false if unreachable. */
    private static boolean removeAt(final JsonNode root, final List<JsonMappingException.Reference> path) {
        if (path.isEmpty()) {
            return false;
        }
        JsonNode cur = root;
        for (int i = 0; i < path.size() - 1; i++) {
            final JsonMappingException.Reference r = path.get(i);
            if (r.getFieldName() != null && cur instanceof ObjectNode) {
                cur = cur.get(r.getFieldName());
            } else if (cur instanceof ArrayNode && r.getIndex() >= 0) {
                cur = cur.get(r.getIndex());
            } else {
                return false;
            }
            if (cur == null) {
                return false;
            }
        }
        final JsonMappingException.Reference last = path.get(path.size() - 1);
        if (last.getFieldName() != null && cur instanceof ObjectNode) {
            ((ObjectNode) cur).remove(last.getFieldName());
            return true;
        }
        if (cur instanceof ArrayNode && last.getIndex() >= 0) {
            ((ArrayNode) cur).set(last.getIndex(), NullNode.getInstance());
            return true;
        }
        return false;
    }

    private static String shortMessage(final JsonMappingException e) {
        final String m = e.getOriginalMessage();
        return m != null ? m : e.getMessage();
    }

    /**
     * Build the POJO's no-arg default by binding an empty object, so a later recovered value can fall
     * back to the field's real default. Null when the type cannot be built without data.
     */
    private T constructDefault() {
        try {
            return mapper.readValue("{}", type);
        } catch (final Exception e) {
            return null;
        }
    }
}
