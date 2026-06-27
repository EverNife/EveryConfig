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

/**
 * JSON-with-comments codec — NOT IMPLEMENTED YET (placeholder driving the test suite).
 *
 * <p>Declared {@link CommentFidelity#LOSSY}: Jackson reads {@code //} and {@code /* *}{@code /} comments
 * but does not write them back natively, and the format-agnostic comment overlay (block/side per path)
 * cannot address every position JSONC permits (after a comma, between array elements). Identity and the
 * shared mapper are wired so binding works; the text&lt;-&gt;tree operations throw until a real emitter +
 * comment parser are built. The companion contract tests are expected to fail on every on-disk
 * round-trip until then.
 */
public final class JsoncCodec implements Codec, ObjectMapperAware, CommentAware {

    /** One shared, isolated default mapper reused across every default-constructed instance. */
    private static final ObjectMapper DEFAULT = FCMapperProfiles.storageSafe(new ObjectMapper());

    private final ObjectMapper mapper;

    public JsoncCodec() {
        this.mapper = DEFAULT;
    }

    public JsoncCodec(final ObjectMapper userMapper) {
        this.mapper = FCMapperProfiles.isolate(userMapper, () -> DEFAULT);
    }

    // ---- identity -------------------------------------------------------

    @Override
    public String formatId() {
        return "jsonc";
    }

    @Override
    public String[] fileExtensions() {
        return new String[]{"jsonc"};
    }

    @Override
    public CommentFidelity commentFidelity() {
        return CommentFidelity.LOSSY;
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
        return new UnsupportedOperationException("JsoncCodec." + op + " is not implemented yet");
    }
}
