package br.com.finalcraft.everyconfig.codec.jackson;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CodecException;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.FCMapperProfiles;
import br.com.finalcraft.everyconfig.codec.ObjectMapperAware;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Strict RFC JSON codec ({@link CommentFidelity#NONE}), the interoperable, machine-readable default.
 * Comments and trailing commas are not accepted on read, and the comment overlay is ignored on write
 * (JSON has no in-band comment syntax that round-trips). Output is pretty-printed because config files
 * are human-opened. Explicit nulls are kept (they are user data); the space-saving variant is the
 * {@code compact} profile, opt-in via a user mapper.
 */
public final class JsonCodec implements Codec, ObjectMapperAware {

    /** One shared, isolated default mapper reused across every default-constructed instance. */
    private static final ObjectMapper DEFAULT = FCMapperProfiles.strictJson(JsonMapper.builder().build());

    private final ObjectMapper mapper;
    private final boolean sidecarDoc;

    public JsonCodec() {
        this.mapper = DEFAULT;
        this.sidecarDoc = false;
    }

    /** Uses an isolated copy of the user's mapper so a later external mutation cannot leak in. */
    public JsonCodec(final ObjectMapper userMapper) {
        this(userMapper, false);
    }

    public JsonCodec(final ObjectMapper userMapper, final boolean sidecarDoc) {
        this.mapper = FCMapperProfiles.isolate(userMapper, () -> DEFAULT);
        this.sidecarDoc = sidecarDoc;
    }

    @Override
    public String formatId() {
        return "json";
    }

    @Override
    public String[] fileExtensions() {
        return new String[]{"json"};
    }

    @Override
    public CommentFidelity commentFidelity() {
        return CommentFidelity.NONE;
    }

    @Override
    public boolean writesSidecarDoc() {
        return sidecarDoc;
    }

    @Override
    public ObjectMapper objectMapper() {
        return mapper;
    }

    @Override
    public JsonNode readTree(final String text) {
        try {
            return mapper.readTree(text);
        } catch (final Exception e) {
            throw new CodecException("failed to parse JSON", e);
        }
    }

    @Override
    public String writeTreePlain(final JsonNode tree) {
        try {
            return mapper.writeValueAsString(tree);
        } catch (final Exception e) {
            throw new CodecException("failed to write JSON", e);
        }
    }

    @Override
    public <V> V treeToValue(final JsonNode node, final JavaType type) {
        try {
            return mapper.convertValue(node, type);
        } catch (final Exception e) {
            throw new CodecException("failed to bind JSON node to " + type, e);
        }
    }

    @Override
    public JsonNode valueToTree(final Object value) {
        try {
            return mapper.valueToTree(value);
        } catch (final Exception e) {
            throw new CodecException("failed to project value to JSON tree", e);
        }
    }
}
