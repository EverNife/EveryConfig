package br.com.finalcraft.finalconfig.binding;

import br.com.finalcraft.finalconfig.codec.Codec;
import br.com.finalcraft.finalconfig.codec.ObjectMapperAware;
import br.com.finalcraft.finalconfig.config.Config;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;

import java.util.Collections;
import java.util.List;

/**
 * A typed view bound to one {@link Config}: it reads a POJO from the canonical tree and (write side,
 * elsewhere) merges a POJO back into it. Reading is a derived view — it never mutates the tree — and is
 * tolerant: unknown keys are ignored, missing keys keep the POJO's constructed defaults, and under the
 * lenient policy a single bad value is recorded as a {@link LoadIssue} rather than failing the load.
 */
public final class EntityBinder<T> {

    private static final DeserializationProblemHandler LENIENT = new LenientProblemHandler();

    private final Config config;
    private final JavaType type;
    private final ObjectMapper mapper;
    private final BindOptions options;

    private List<LoadIssue> lastIssues = Collections.emptyList();

    public EntityBinder(final Config config, final JavaType type, final Codec codec, final BindOptions options) {
        if (!(codec instanceof ObjectMapperAware)) {
            throw new BindException("codec " + codec.formatId() + " does not expose an ObjectMapper for binding");
        }
        this.config = config;
        this.type = type;
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
