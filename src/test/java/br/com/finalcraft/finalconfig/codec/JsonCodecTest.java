package br.com.finalcraft.finalconfig.codec;

import br.com.finalcraft.finalconfig.codec.jackson.JsonCodec;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonCodecTest {

    /** Minimal POJO with public fields for an entity binding round-trip. */
    public static final class Pojo {
        public String name;
        public int count;
        public boolean active;

        public Pojo() {
        }

        public Pojo(final String name, final int count, final boolean active) {
            this.name = name;
            this.count = count;
            this.active = active;
        }
    }

    @Test
    void fidelityIsNone() {
        assertEquals(CommentFidelity.NONE, new JsonCodec().commentFidelity());
    }

    @Test
    void treeRoundTripPreservesData() {
        final JsonCodec codec = new JsonCodec();
        final String json = "{\"a\":1,\"b\":{\"c\":\"x\",\"d\":[1,2,3]},\"e\":null}";
        final JsonNode tree = codec.readTree(json);
        final JsonNode reparsed = codec.readTree(codec.writeTreePlain(tree));
        assertEquals(tree, reparsed);
    }

    @Test
    void pojoBindingRoundTrip() {
        final JsonCodec codec = new JsonCodec();
        final Pojo original = new Pojo("hello", 42, true);
        final JsonNode tree = codec.valueToTree(original);

        final ObjectMapper mapper = codec.objectMapper();
        final JavaType type = mapper.getTypeFactory().constructType(Pojo.class);
        final Pojo restored = codec.treeToValue(tree, type);

        assertEquals("hello", restored.name);
        assertEquals(42, restored.count);
        assertTrue(restored.active);
    }

    @Test
    void unknownKeysSurviveBinding() {
        final JsonCodec codec = new JsonCodec();
        final JsonNode tree = codec.readTree("{\"name\":\"x\",\"count\":1,\"active\":false,\"extra\":\"ignored\"}");
        final JavaType type = codec.objectMapper().getTypeFactory().constructType(Pojo.class);
        // An unknown property must not fail binding (FAIL_ON_UNKNOWN_PROPERTIES is off).
        final Pojo restored = codec.treeToValue(tree, type);
        assertEquals("x", restored.name);
        // The unknown key still lives in the tree.
        assertTrue(tree instanceof ObjectNode);
        assertNotNull(tree.get("extra"));
        assertEquals("ignored", tree.get("extra").asText());
    }

    @Test
    void explicitNullIsKeptOnWrite() {
        final JsonCodec codec = new JsonCodec();
        final ObjectNode root = (ObjectNode) codec.readTree("{\"a\":null}");
        final String text = codec.writeTreePlain(root);
        assertTrue(text.contains("\"a\""), "explicit null key must survive: " + text);
        assertTrue(text.contains("null"), "explicit null value must survive: " + text);
    }

    @Test
    void strictJsonRejectsComments() {
        final JsonCodec codec = new JsonCodec();
        assertThrows(CodecException.class,
                () -> codec.readTree("{\n// a comment\n\"a\":1\n}"));
    }
}
