package br.com.finalcraft.everyconfig.binding;

import br.com.finalcraft.everyconfig.annotation.Comment;
import br.com.finalcraft.everyconfig.annotation.CommentMode;
import br.com.finalcraft.everyconfig.annotation.Key;
import br.com.finalcraft.everyconfig.annotation.KeyTransformCase;
import br.com.finalcraft.everyconfig.annotation.PostLoad;
import br.com.finalcraft.everyconfig.codec.CommentAware.CommentLoad;
import br.com.finalcraft.everyconfig.codec.jackson.YamlCodec;
import br.com.finalcraft.everyconfig.config.Config;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The full load -> bind -> mutate -> merge -> emit cycle: the tree wins (unknown user key survives), the
 *  POJO drives its own fields, and comments (seeded + user) round-trip. */
class BindingEndToEndTest {

    @Comment(value = "Connection settings", mode = CommentMode.SET_IF_ABSENT)
    static class DbConfig {
        @Key(transformCase = KeyTransformCase.KEBAB_CASE)
        @Comment("JDBC url; edit me and your comment is kept")
        public String jdbcUrl = "jdbc:h2:mem:test"; // -> key "jdbc-url"

        @JsonProperty("max-pool")
        public int maxPool = 10;

        @Comment("pool timeout in seconds")
        public int timeout = 30; // a NEW key, absent from the on-disk file

        @PostLoad
        void validate() {
            if (maxPool < 1) {
                throw new IllegalStateException("max-pool must be >= 1");
            }
        }
    }

    private final YamlCodec yaml = new YamlCodec();

    @Test
    void loadBindMutateMergeEmit() {
        final String disk =
                "# my header\n"
              + "\n"
              + "jdbc-url: mydb # user edited\n"
              + "max-pool: 20\n"
              + "legacy-key: keepme\n";

        final CommentLoad load = yaml.readComments(disk);
        final Config c = new Config((ObjectNode) yaml.readTree(disk), load.comments, load.keyOrder);

        final DbConfig db = c.loadAs(DbConfig.class, yaml);
        assertEquals("mydb", db.jdbcUrl);
        assertEquals(20, db.maxPool);

        db.maxPool = 25;
        c.bind(DbConfig.class, yaml).write("", db);

        // Tree wins: the hand-added unknown key survives the binding save.
        assertTrue(c.contains("legacy-key"));
        assertEquals(25, c.getInt("max-pool"));

        final String emitted = yaml.writeWithComments(c.getRoot(), c.getCommentTree(), c.getFileKeyOrder());
        assertTrue(emitted.contains("# my header"), emitted);                 // file header preserved
        assertTrue(emitted.contains("user edited"), emitted);                 // user side comment preserved
        assertTrue(emitted.contains("max-pool: 25"), emitted);               // POJO value merged in
        assertTrue(emitted.contains("legacy-key: keepme"), emitted);         // unknown key emitted
        assertTrue(emitted.contains("# pool timeout in seconds"), emitted);  // seed fires for the new key
        // jdbc-url had no block comment in the file, so its @Comment documentation is seeded onto it.
        assertTrue(emitted.contains("# JDBC url; edit me"), emitted);
    }
}
