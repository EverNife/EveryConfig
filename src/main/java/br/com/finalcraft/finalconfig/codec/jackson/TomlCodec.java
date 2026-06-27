package br.com.finalcraft.finalconfig.codec.jackson;

import br.com.finalcraft.finalconfig.codec.Codec;
import br.com.finalcraft.finalconfig.codec.CommentAware;
import br.com.finalcraft.finalconfig.codec.CommentFidelity;
import br.com.finalcraft.finalconfig.codec.FCMapperProfiles;
import br.com.finalcraft.finalconfig.codec.ObjectMapperAware;
import br.com.finalcraft.finalconfig.core.KeyOrder;
import br.com.finalcraft.finalconfig.core.comment.CommentTree;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

/**
 * TOML codec — NOT IMPLEMENTED YET (placeholder driving the test suite).
 *
 * <p>Identity and the shared {@link ObjectMapper} are wired so that an in-memory bind round-trip and an
 * absent-file {@code Config.open} already work; but the text&lt;-&gt;tree operations (parsing, the
 * structure emitter, the comment overlay) throw until the real implementation lands. The companion
 * codec-contract tests are therefore expected to fail on every on-disk round-trip until then, which is
 * exactly the signal that should drive building this class out.
 */
public final class TomlCodec implements Codec, ObjectMapperAware, CommentAware {

    /** One shared, isolated default mapper reused across every default-constructed instance. */
    private static final ObjectMapper DEFAULT = FCMapperProfiles.storageSafe(new TomlMapper());

    private final ObjectMapper mapper;

    public TomlCodec() {
        this.mapper = DEFAULT;
    }

    public TomlCodec(final ObjectMapper userMapper) {
        this.mapper = FCMapperProfiles.isolate(userMapper, () -> DEFAULT);
    }

    // ---- identity -------------------------------------------------------

    @Override
    public String formatId() {
        return "toml";
    }

    @Override
    public String[] fileExtensions() {
        return new String[]{"toml"};
    }

    @Override
    public CommentFidelity commentFidelity() {
        return CommentFidelity.LOSSLESS;
    }

    @Override
    public ObjectMapper objectMapper() {
        return mapper;
    }

    // ---- entity <-> tree (works today; shared mapper) -------------------

    @Override
    public <V> V treeToValue(final JsonNode node, final JavaType type) {
        return mapper.convertValue(node, type);
    }

    @Override
    public JsonNode valueToTree(final Object value) {
        return mapper.valueToTree(value);
    }

    // ---- text <-> tree (NOT IMPLEMENTED) -------------------------------

    @Override
    public JsonNode readTree(final String text) {
        throw notImplemented("readTree");
    }

    @Override
    public String writeTreePlain(final JsonNode tree) {
        throw notImplemented("writeTreePlain");
    }

    @Override
    public CommentLoad readComments(final String text) {
        throw notImplemented("readComments");
    }

    @Override
    public String writeWithComments(final JsonNode tree, final CommentTree commentTree, final KeyOrder keyOrder) {
        throw notImplemented("writeWithComments");
    }

    @Override
    public String writeScalar(final Object leaf) {
        throw notImplemented("writeScalar");
    }

    private static UnsupportedOperationException notImplemented(final String op) {
        return new UnsupportedOperationException("TomlCodec." + op + " is not implemented yet");
    }
}
