package br.com.finalcraft.finalconfig.config;

import br.com.finalcraft.finalconfig.config.section.ConfigSection;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for the dynamic path API over the canonical ObjectNode. */
public class ConfigDynamicApiTest {

    @Test
    void setValueAutoVivifiesNestedPaths() {
        final Config c = new Config();
        c.setValue("database.pool.maxSize", 25);

        assertEquals(25, c.getInt("database.pool.maxSize"));
        assertTrue(c.contains("database"));
        assertTrue(c.contains("database.pool"));
        assertEquals(1, c.getKeys("database").size());
        // intermediate vivified nodes are objects
        assertTrue(c.getConfigurationSection("database.pool") != null);
        // a scalar path is not a section
        assertNull(c.getConfigurationSection("database.pool.maxSize"));
    }

    @Test
    void legacyStringifiedLongAndStringToleranceRead() {
        final Config c = new Config();
        c.setValue("ln", "1700000000000"); // stored as TextNode (legacy quoted long)
        c.setValue("lf", "1.0");            // legacy long written with trailing .0
        c.setValue("le", "");               // empty string
        c.setValue("port", "25565");        // quoted int
        c.setValue("ratio", "3.14");        // quoted double

        assertEquals(1700000000000L, c.getLong("ln"));
        assertEquals(1L, c.getLong("lf"));
        assertEquals(7L, c.getLong("le", 7L)); // empty -> def
        assertEquals(25565, c.getInt("port"));
        assertEquals(3.14, c.getDouble("ratio"), 1e-9);
    }

    @Test
    void getStringOnListJoinsWithNewline() {
        final Config c = new Config();
        c.setValue("message", Arrays.asList("line1", "line2", "line3"));
        assertEquals("line1\nline2\nline3", c.getString("message"));
        assertEquals(Arrays.asList("line1", "line2", "line3"), c.getStringList("message"));
    }

    @Test
    void trichotomyAbsentNullValue() {
        final Config c = new Config();
        c.setValue("present", 5);
        c.setValue("explicitNull", NullNode.getInstance()); // explicit null, NOT a delete

        // absent
        assertFalse(c.contains("missing"));
        assertNull(c.getValue("missing"));
        assertEquals(9, c.getInt("missing", 9));

        // explicit null: present, but value getters flatten to def
        assertTrue(c.contains("explicitNull"));
        assertNull(c.getValue("explicitNull"));
        assertEquals(9, c.getInt("explicitNull", 9));

        // real value
        assertTrue(c.contains("present"));
        assertEquals(5, c.getInt("present"));
    }

    @Test
    void javaNullDeletesAndRemoveValueWorks() {
        final Config c = new Config();
        c.setValue("a", 1);
        c.setValue("b.c", 2);

        c.setValue("a", null); // Java null == delete
        assertFalse(c.contains("a"));

        assertTrue(c.removeValue("b.c"));
        assertFalse(c.contains("b.c"));
        assertTrue(c.contains("b")); // parent survives
    }

    @Test
    void deepKeysReturnDottedDescendantPaths() {
        final Config c = new Config();
        c.setValue("server.host", "localhost");
        c.setValue("server.port", 8080);
        c.setValue("server.tls.enabled", true);

        assertEquals(3, c.getKeys("server").size()); // host, port, tls
        // direct children of server: host, port, tls
        assertTrue(c.getKeys("server").contains("host"));
        assertTrue(c.getKeys("server").contains("port"));
        assertTrue(c.getKeys("server").contains("tls"));

        final java.util.Set<String> deep = c.getKeys("", true);
        assertTrue(deep.contains("server"));
        assertTrue(deep.contains("server.host"));
        assertTrue(deep.contains("server.tls.enabled"));
    }

    @Test
    void getOrSetDefaultValueSeedsDataAndRecastsNumbers() {
        final Config c = new Config();

        // absent -> seeds default, flags dirty
        final int v = c.getOrSetDefaultValue("limits.max", 100);
        assertEquals(100, v);
        assertTrue(c.contains("limits.max"));
        assertTrue(c.isNewDefaultValueToSave());

        // present as a LongNode, default is Integer -> recast to Integer, no ClassCastException
        c.setValue("count", 7L);
        final Integer recast = c.getOrSetDefaultValue("count", 5);
        assertEquals(7, recast.intValue());
    }

    @Test
    void seedCommentObeysFileWins() {
        final Config c = new Config();

        // seed on a fresh path
        c.getOrSetDefaultValue("a.timeout", 30, "request timeout in seconds");
        assertEquals("request timeout in seconds", c.getComment("a.timeout"));

        // an authoritative (explicit) comment must NOT be overridden by a later seed
        c.setComment("a.retries", "USER AUTHORED");
        c.getOrSetDefaultValue("a.retries", 3, "SEED THAT MUST BE IGNORED");
        assertEquals("USER AUTHORED", c.getComment("a.retries"));
        assertEquals(3, c.getInt("a.retries")); // data still seeded
    }

    @Test
    void configSectionDelegatesToOwningConfig() {
        final Config c = new Config();
        final ConfigSection db = c.getConfigSection("database");
        db.setValue("url", "jdbc:postgresql://localhost/db");
        db.setValue("pool.size", 10);

        assertEquals("jdbc:postgresql://localhost/db", c.getString("database.url"));
        assertEquals(10, c.getInt("database.pool.size"));
        assertEquals("database", db.getSectionKey());
        assertEquals(10, db.getInt("pool.size"));
        assertTrue(db.contains("url"));
        assertTrue(db.getKeys().contains("url"));
    }

    @Test
    void getRootEscapeHatchAndUnknownKeysSurvive() {
        final Config c = new Config();
        c.setValue("known", 1);
        // raw tree mutation: the tree is the single source of truth
        c.getRoot().put("unknown", "value-not-via-api");

        assertTrue(c.contains("unknown"));
        assertEquals("value-not-via-api", c.getString("unknown"));

        // further API mutations don't drop the unknown key
        c.setValue("another", 2);
        assertTrue(c.contains("unknown"));
        assertEquals(1, c.getInt("known"));
        assertEquals(2, c.getInt("another"));
    }
}
