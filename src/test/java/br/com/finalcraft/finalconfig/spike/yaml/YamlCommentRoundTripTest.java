package br.com.finalcraft.finalconfig.spike.yaml;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * De-risk proof for the comment overlay: read commented YAML into an ObjectNode + CommentTree, mutate
 * the tree (change a value, add a key with a seeded comment), re-emit, and assert that existing
 * comments are preserved, the value change is reflected, and the seed appears — then that the output
 * survives a second parse (comments still recoverable).
 */
public class YamlCommentRoundTripTest {

    private static final YAMLMapper MAPPER = YAMLMapper.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build();

    private static final String SOURCE = String.join("\n",
            "# Server config",
            "# second header line",
            "host: localhost # the host",
            "port: 8080",
            "",
            "# database section",
            "database:",
            "  # the JDBC url",
            "  url: jdbc-postgresql-localhost-db",
            "  pool-size: 10",
            "");

    @Test
    void parserRecoversBlockAndSideComments() {
        final CommentTree comments = new YamlCommentParser().parse(SOURCE);

        assertEquals("# Server config\n# second header line", comments.block("host"));
        assertTrue(comments.side("host").contains("the host"), "side comment on host");
        assertEquals("# database section", comments.block("database"));
        assertEquals("# the JDBC url", comments.block("database.url"));
    }

    @Test
    void roundTripPreservesCommentsReflectsMutationAndSeeds() throws Exception {
        final ObjectNode root = (ObjectNode) MAPPER.readTree(SOURCE);
        final CommentTree comments = new YamlCommentParser().parse(SOURCE);

        // Mutate the canonical tree.
        root.put("port", 9090);                 // change existing value
        root.put("max-connections", 100);       // new key
        comments.seedBlock("max-connections", "maximum concurrent connections"); // code-seeded comment
        comments.seedBlock("database.url", "SEED THAT MUST BE IGNORED"); // path already has a file comment

        final String out = new YamlCommentEmitter(comments).emit(root);

        // Existing comments preserved.
        assertTrue(out.contains("# Server config"), out);
        assertTrue(out.contains("# second header line"), out);
        assertTrue(out.contains("# database section"), out);
        assertTrue(out.contains("# the JDBC url"), out);
        // Side comment preserved.
        assertTrue(out.contains("host: localhost # the host"), out);
        // Mutation reflected.
        assertTrue(out.contains("port: 9090"), out);
        // Seed for the new key present.
        assertTrue(out.contains("# maximum concurrent connections"), out);
        assertTrue(out.contains("max-connections: 100"), out);
        // Decision #1: a seed on a path that already has a file comment is IGNORED (user/file wins).
        assertTrue(out.contains("# the JDBC url"), out);
        assertTrue(!out.contains("SEED THAT MUST BE IGNORED"), out);

        // Second round-trip: comments are still recoverable from the emitted text.
        final CommentTree reparsed = new YamlCommentParser().parse(out);
        assertEquals("# the JDBC url", reparsed.block("database.url"));
        assertEquals("# database section", reparsed.block("database"));
        assertTrue(reparsed.block("max-connections").contains("maximum concurrent connections"));

        // And the data still parses back to the mutated values.
        final ObjectNode reloaded = (ObjectNode) MAPPER.readTree(out);
        assertEquals(9090, reloaded.get("port").asInt());
        assertEquals(100, reloaded.get("max-connections").asInt());
        assertEquals("localhost", reloaded.get("host").asText());
        assertEquals(10, reloaded.get("database").get("pool-size").asInt());
    }
}
