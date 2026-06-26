package br.com.finalcraft.finalconfig.binding;

import br.com.finalcraft.finalconfig.annotation.Comment;
import br.com.finalcraft.finalconfig.codec.Codec;
import br.com.finalcraft.finalconfig.codec.CommentFidelity;
import br.com.finalcraft.finalconfig.codec.ObjectMapperAware;
import br.com.finalcraft.finalconfig.config.Config;
import br.com.finalcraft.finalconfig.core.comment.CommentTree;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
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

    private static final DeserializationProblemHandler LENIENT = new LenientProblemHandler();

    /** One schema cache per shared codec mapper, so a type is introspected once and reused. */
    private static final ConcurrentHashMap<ObjectMapper, SchemaCache> SCHEMA_CACHES = new ConcurrentHashMap<>();

    private final Config config;
    private final JavaType type;
    private final Codec codec;
    private final ObjectMapper mapper;
    private final BindOptions options;

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
        SmartMerge.mergeInto(target, (ObjectNode) candidate, schema(), options);
        seedCommentsFromAnnotations(pojo.getClass(), basePath);
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
        if (classComment != null && basePath.isEmpty() && comments.getHeader().isEmpty()) {
            comments.setHeader(Arrays.asList(classComment.value()));
        }
        for (final Field f : BindingNames.allFields(clazz)) {
            final Comment c = f.getAnnotation(Comment.class);
            if (c == null) {
                continue;
            }
            final String key = BindingNames.keyFor(f);
            final String path = basePath.isEmpty() ? key : basePath + config.pathSeparator() + key;
            if (!comments.hasUserComment(path)) {
                comments.seedComment(path, String.join("\n", c.value()));
            }
        }
    }

    private T doBind(final T base, final JsonNode source) {
        LoadIssueCollector.begin();
        T result;
        try {
            if (base != null) {
                ObjectReader reader = mapper.readerForUpdating(base);
                if (options.coercion() == BindOptions.Coercion.LENIENT) {
                    reader = reader.withHandler(LENIENT);
                }
                result = reader.readValue(source);
            } else if (options.coercion() == BindOptions.Coercion.LENIENT) {
                result = mapper.reader().forType(type).withHandler(LENIENT).readValue(source);
            } else {
                result = mapper.convertValue(source, type);
            }
        } catch (final BindException e) {
            throw e;
        } catch (final Exception e) {
            throw new BindException("failed to bind tree to " + type, e);
        } finally {
            lastIssues = LoadIssueCollector.end();
        }
        if (result instanceof LoadIssueAware) {
            ((LoadIssueAware) result).setLoadIssues(lastIssues);
        }
        PostInjectInvoker.invoke(result, lastIssues);
        return result;
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
