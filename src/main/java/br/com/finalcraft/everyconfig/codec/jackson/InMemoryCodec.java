package br.com.finalcraft.everyconfig.codec.jackson;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CodecException;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.ECMapperProfiles;
import br.com.finalcraft.everyconfig.selfdescribe.AnnotationCompactElementResolver;
import br.com.finalcraft.everyconfig.selfdescribe.CompactElementResolver;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The codec for a Config that lives entirely in memory: it carries a Jackson {@link ObjectMapper} (so the
 * full POJO/typed flow works — {@code setValue(path, pojo)} merge, {@code getValue(path, type)},
 * {@code @Key}/{@code @Section}/{@code @Comment}, enum-by-name, {@code java.time}, {@code Optional}) but it
 * has NO on-disk format, so it cannot serialize to text. {@link #readTree}/{@link #writeTreePlain} throw:
 * to persist, give the Config a real codec via {@link br.com.finalcraft.everyconfig.config.Config#save(Codec)}
 * or {@code changeCodec(...)}.
 *
 * <p>It declares no file extensions and {@link CommentFidelity#NONE}, so it is never chosen by file-name
 * inference and never registered for {@code Config.open}. Reach it through {@code Config.inMemory()} or
 * {@link #INSTANCE}. The mapper is the storage-safe default ({@code EveryConfigModule} + jsr310 + jdk8), so
 * binding behaves identically to the file codecs — only the text edge differs.
 */
public final class InMemoryCodec implements Codec {

    private static final ObjectMapper DEFAULT = ECMapperProfiles.storageSafe(JsonMapper.builder().build());

    /** Shared default instance; the common entry point for an in-memory Config. (Declared after
     *  {@link #DEFAULT} so the constructor sees a non-null mapper during static init.) */
    public static final InMemoryCodec INSTANCE = new InMemoryCodec();

    private final ObjectMapper mapper;
    private final CompactElementResolver compactResolver;

    public InMemoryCodec() {
        this.mapper = DEFAULT;
        this.compactResolver = AnnotationCompactElementResolver.INSTANCE;
    }

    /** Uses an isolated copy of the user's mapper so a later external mutation cannot leak in. */
    public InMemoryCodec(final ObjectMapper userMapper) {
        this(userMapper, null);
    }

    /** As {@link #InMemoryCodec(ObjectMapper)}, plus a consumer {@link CompactElementResolver} consulted AHEAD
     *  of the annotation resolver when classifying a collection's element for its compact form. */
    public InMemoryCodec(final ObjectMapper userMapper, final CompactElementResolver compactResolver) {
        this.mapper = ECMapperProfiles.isolate(userMapper, () -> DEFAULT);
        this.compactResolver = CompactElementResolver.compose(compactResolver, AnnotationCompactElementResolver.INSTANCE);
    }

    @Override
    public String formatId() {
        return "memory";
    }

    @Override
    public String[] fileExtensions() {
        return new String[0]; // never selected by extension; never registered
    }

    @Override
    public CommentFidelity commentFidelity() {
        return CommentFidelity.NONE;
    }

    @Override
    public ObjectMapper getObjectMapper() {
        return mapper;
    }

    @Override
    public CompactElementResolver compactElementResolver() {
        return compactResolver;
    }

    @Override
    public JsonNode readTree(final String text) {
        throw new CodecException(
                "the in-memory codec has no text format; open the file with a real codec (.yml/.json/.toml/.jsonc)");
    }

    @Override
    public String writeTreePlain(final JsonNode tree) {
        throw new CodecException(
                "the in-memory codec has no text format; persist with save(realCodec) or changeCodec(realCodec)");
    }

    @Override
    public <V> V treeToValue(final JsonNode node, final JavaType type) {
        try {
            return mapper.convertValue(node, type);
        } catch (final Exception e) {
            throw new CodecException("failed to bind node to " + type, e);
        }
    }

    @Override
    public JsonNode valueToTree(final Object value) {
        try {
            return mapper.valueToTree(value);
        } catch (final Exception e) {
            throw new CodecException("failed to project value to a tree", e);
        }
    }
}
