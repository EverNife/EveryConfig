package br.com.finalcraft.finalconfig.config;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Decision-#1 reconciliation: a seed fires only the first time a path is written, and migration moves
 *  data + comment while marking the destination persisted. */
class CommentReconcileTest {

    /** A path that already existed in the loaded file but carries no comment must NOT be re-seeded —
     *  the user deleted that comment and the deletion has to stick across saves. */
    @Test
    void seedSuppressedForPreviouslyPersistedPath() {
        final ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.putObject("a").put("b", 5); // loaded file already had a.b = 5, no comment
        final Config c = new Config(root);

        c.getOrSetDefaultValue("a.b", 99, "SEED THAT MUST NOT FIRE");

        assertEquals(5, c.getInt("a.b"));       // existing value kept
        assertNull(c.getComment("a.b"));        // seed suppressed: deletion sticks
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
