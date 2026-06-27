package br.com.finalcraft.everyconfig.binding;

import br.com.finalcraft.everyconfig.annotation.Key;
import br.com.finalcraft.everyconfig.annotation.KeyTransformCase;
import br.com.finalcraft.everyconfig.codec.jackson.JsonCodec;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The annotation introspector wired into every codec mapper: @Key rename + case transform, enum-by-name. */
class AnnotationBindingTest {

    enum Mode {FAST, SLOW}

    static class Server {
        @Key(transformCase = KeyTransformCase.KEBAB_CASE)
        public int maxPoolSize = 10;

        @Key("custom-host")
        public String host = "localhost";

        public Mode mode = Mode.FAST;
    }

    private final JsonCodec codec = new JsonCodec();

    @Test
    void keyRenameAndCaseTransformApplied() {
        final JsonNode tree = codec.valueToTree(new Server());
        assertTrue(tree.has("max-pool-size"), tree.toString());
        assertTrue(tree.has("custom-host"), tree.toString());
        assertEquals(10, tree.get("max-pool-size").asInt());
        assertEquals("localhost", tree.get("custom-host").asText());
    }

    @Test
    void enumSerializesByName() {
        assertEquals("FAST", codec.valueToTree(new Server()).get("mode").asText());
    }

    @Test
    void boundBackThroughRenamedKeys() {
        final JsonNode tree = codec.readTree("{\"max-pool-size\":25,\"custom-host\":\"h\",\"mode\":\"SLOW\"}");
        final JavaType type = codec.objectMapper().constructType(Server.class);
        final Server s = codec.treeToValue(tree, type);
        assertEquals(25, s.maxPoolSize);
        assertEquals("h", s.host);
        assertEquals(Mode.SLOW, s.mode);
    }
}
