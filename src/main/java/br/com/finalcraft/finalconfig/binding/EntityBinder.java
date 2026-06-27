package br.com.finalcraft.finalconfig.binding;
import br.com.finalcraft.finalconfig.binding.merge.PostInjectInvoker;
import br.com.finalcraft.finalconfig.binding.merge.SmartMerge;
import br.com.finalcraft.finalconfig.binding.schema.BindingNames;
import br.com.finalcraft.finalconfig.binding.schema.Schema;
import br.com.finalcraft.finalconfig.binding.schema.SchemaCache;

import br.com.finalcraft.finalconfig.annotation.Comment;
import br.com.finalcraft.finalconfig.annotation.CommentMode;
import br.com.finalcraft.finalconfig.annotation.Section;
import br.com.finalcraft.finalconfig.codec.Codec;
import br.com.finalcraft.finalconfig.codec.CommentFidelity;
import br.com.finalcraft.finalconfig.codec.ObjectMapperAware;
import br.com.finalcraft.finalconfig.config.Config;
import br.com.finalcraft.finalconfig.core.comment.CommentTree;
import br.com.finalcraft.finalconfig.core.comment.CommentType;
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
import java.util.Collections;
import java.util.List;
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

    /** A field placed under a nested path by {@code @Section}: its flat key and its full dotted location. */
    private static final class SectionField {
        final String flatKey;   // the key the mapper emits the field as, e.g. "max-size"
        final String fullPath;  // its on-disk location, e.g. "database.pool.max-size"

        SectionField(final String flatKey, final String fullPath) {
            this.flatKey = flatKey;
            this.fullPath = fullPath;
        }
    }

    private static List<SectionField> collectSectionFields(final Class<?> raw) {
        final List<SectionField> out = new ArrayList<>();
        for (final Field f : BindingNames.allFields(raw)) {
            final Section s = f.getAnnotation(Section.class);
            if (s != null && !s.value().isEmpty()) {
                final String key = BindingNames.keyFor(f);
                out.add(new SectionField(key, s.value() + "." + key));
            }
        }
        return out;
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

    /** Issues from the most recent bind on this binder (empty when clean). */
    public List<LoadIssue> lastLoadIssues() {
        return lastIssues;
    }

    // ---- WRITE: POJO -> tree (merge, never replace) --------------------

    /**
     * Project {@code pojo} to a tree and merge it into the canonical tree at the root. Unknown file keys,
     * the comment overlay, and key order all survive; the POJO is the source of truth only for the keys
     * it declares. Comments from {@code @Comment} are seeded (never written over a user edit). This
     * mutates the in-memory tree only; persisting it is the backend's job.
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
        // @Section fields are emitted flat by the mapper; move them to their nested location before merge.
        // Obsolete pruning is forced off when sections are present: the schema is flat while the tree is
        // nested, so a relocated section would otherwise look obsolete.
        final BindOptions mergeOptions;
        if (sectionFields.isEmpty()) {
            mergeOptions = options;
        } else {
            relocateForWrite(candObj);
            mergeOptions = options.withObsoletePolicy(BindOptions.ObsoletePolicy.PRESERVE);
        }
        SmartMerge.mergeInto(target, candObj, schema(), mergeOptions);
        seedCommentsFromAnnotations(pojo.getClass(), basePath);
    }

    /** Surface each {@code @Section} field's nested value at its flat key so the mapper can bind it. */
    private JsonNode viewForRead(final JsonNode source) {
        if (!(source instanceof ObjectNode)) {
            return source;
        }
        final ObjectNode copy = (ObjectNode) source.deepCopy();
        for (final SectionField sf : sectionFields) {
            final JsonNode node = getAtPath(copy, sf.fullPath);
            if (node != null) {
                copy.set(sf.flatKey, node);
            }
        }
        return copy;
    }

    /** Move each {@code @Section} field from its flat key to its nested location in the candidate tree. */
    private void relocateForWrite(final ObjectNode candidate) {
        for (final SectionField sf : sectionFields) {
            if (candidate.has(sf.flatKey)) {
                setAtPath(candidate, sf.fullPath, candidate.remove(sf.flatKey));
            }
        }
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
