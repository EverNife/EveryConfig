package br.com.finalcraft.finalconfig.codec;

import br.com.finalcraft.finalconfig.codec.CommentAware.CommentLoad;
import br.com.finalcraft.finalconfig.codec.jackson.YamlCodec;
import br.com.finalcraft.finalconfig.core.comment.CommentType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlCodecTest {

    @Test
    void fidelityIsLossless() {
        assertEquals(CommentFidelity.LOSSLESS, new YamlCodec().commentFidelity());
    }

    @Test
    void dataTreeRoundTrip() {
        final YamlCodec codec = new YamlCodec();
        final String yaml = "server:\n  host: localhost\n  port: 8080\nlist:\n- a\n- b\n";
        final JsonNode tree = codec.readTree(yaml);
        final JsonNode reparsed = codec.readTree(codec.writeTreePlain(tree));
        assertEquals(tree, reparsed);
    }

    @Test
    void commentRoundTrip() {
        final YamlCodec codec = new YamlCodec();
        final String yaml =
                "# top level block\n"
              + "server:\n"
              + "  # the host block comment\n"
              + "  host: localhost # side on host\n"
              + "  port: 8080\n";

        final CommentLoad load = codec.readComments(yaml);

        // Comments recovered by path.
        assertEquals("top level block", load.comments.getComment("server", CommentType.BLOCK));
        assertEquals("the host block comment", load.comments.getComment("server.host", CommentType.BLOCK));
        assertEquals("side on host", load.comments.getComment("server.host", CommentType.SIDE));

        final JsonNode tree = codec.readTree(yaml);
        final String emitted = codec.writeWithComments(tree, load.comments, load.keyOrder);

        // Comments survive the re-emit.
        assertTrue(emitted.contains("# top level block"), emitted);
        assertTrue(emitted.contains("# the host block comment"), emitted);
        assertTrue(emitted.contains("# side on host"), emitted);

        // Data still round-trips through readTree.
        assertEquals(tree, codec.readTree(emitted));
    }

    @Test
    void keyOrderIsHonored() {
        final YamlCodec codec = new YamlCodec();
        final String yaml = "zebra: 1\napple: 2\nmango: 3\n";
        final CommentLoad load = codec.readComments(yaml);
        final JsonNode tree = codec.readTree(yaml);
        final String emitted = codec.writeWithComments(tree, load.comments, load.keyOrder);
        assertTrue(emitted.indexOf("zebra") < emitted.indexOf("apple"), emitted);
        assertTrue(emitted.indexOf("apple") < emitted.indexOf("mango"), emitted);
    }

    @Test
    void writeScalarThrowsOnPopulatedContainer() {
        final YamlCodec codec = new YamlCodec();
        final ObjectNode populated = JsonNodeFactory.instance.objectNode();
        populated.put("child", 1);
        assertThrows(CodecException.class, () -> codec.writeScalar(populated));
    }

    @Test
    void writeScalarAcceptsEmptyContainerAndScalar() {
        final YamlCodec codec = new YamlCodec();
        // A scalar yields a single inline token.
        assertNotNull(codec.writeScalar(JsonNodeFactory.instance.textNode("hi")));
        // An empty container is a legitimate flow value, not smuggled structure.
        assertNotNull(codec.writeScalar(JsonNodeFactory.instance.arrayNode()));
        assertNotNull(codec.writeScalar(JsonNodeFactory.instance.objectNode()));
    }
}
