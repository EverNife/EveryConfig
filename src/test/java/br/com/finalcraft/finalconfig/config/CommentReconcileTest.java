package br.com.finalcraft.finalconfig.config;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Decision-#1 reconciliation: a seed fires only the first time a path is written, and migration moves
 *  data + comment while marking the destination persisted. */
class CommentReconcileTest {

    /** A code-supplied comment is documentation: it is (re)written whenever the path has no comment,
     *  including a pre-existing key that never had one, or one the user deleted. */
    @Test
    void seedAppliesDocumentationToAKeyWithoutAComment() {
        final ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.putObject("a").put("b", 5); // existing key, no comment
        final Config c = new Config(root);

        c.getOrSetDefaultValue("a.b", 99, "documented");

        assertEquals(5, c.getInt("a.b"));                  // existing value kept
        assertEquals("documented", c.getComment("a.b"));   // documentation seeded onto the existing key
    }

    /** An existing comment is never clobbered by a default-comment write. */
    @Test
    void seedDoesNotOverrideAnExistingComment() {
        final ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("k", 1);
        final Config c = new Config(root);
        c.setComment("k", "user comment");
        c.getOrSetDefaultValue("k", 2, "SEED IGNORED");
        assertEquals("user comment", c.getComment("k"));
    }

    /** The two fluent modes: setDefaultComment respects an existing comment, setComment overwrites it. */
    @Test
    void fluentSetDefaultCommentRespectsExistingButSetCommentOverwrites() {
        final Config c = new Config();
        c.setValue("k", 1);

        c.setDefaultComment("k", "first");
        assertEquals("first", c.getComment("k"));   // written: was absent

        c.setDefaultComment("k", "second");
        assertEquals("first", c.getComment("k"));   // kept: already present

        c.setComment("k", "forced");
        assertEquals("forced", c.getComment("k"));  // overwritten
    }

    /** A genuinely new path (absent at load) gets its seed on first write. */
    @Test
    void seedFiresForNewPath() {
        final Config c = new Config();
        c.getOrSetDefaultValue("x.y", 7, "fresh seed");
        assertEquals("fresh seed", c.getComment("x.y"));
    }

    @Test
    void migrateKeyMovesDataAndCommentAndMarksPersisted() {
        final ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("oldName", "val");
        final Config c = new Config(root);
        c.setComment("oldName", "doc for old");

        c.migrateKey("oldName", "newName");

        assertFalse(c.contains("oldName"));
        assertEquals("val", c.getString("newName"));
        assertEquals("doc for old", c.getComment("newName"));

        // newName is now treated as persisted, so a later seed cannot overwrite the migrated comment.
        c.getOrSetDefaultValue("newName", "other", "SEED IGNORED");
        assertEquals("doc for old", c.getComment("newName"));
    }
}
