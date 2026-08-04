package br.com.finalcraft.everyconfig.config;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unified leaf write/remove navigation: the dotted and the {@code [n]} bracket grammar share one walk,
 * so the same growth/clobber rules hold for both. Uses a codec-less {@code Config} (native tree values).
 */
class ConfigLeafNavigationTest {

    @Test
    void dottedAndBracketWritesShareOneWalk() {
        final Config c = new Config();
        c.setValue("a.b.c", 1); // dotted: missing intermediate keys are minted as objects
        assertEquals(1, c.getInt("a.b.c"));

        c.setValue("list", Arrays.asList("x", "y", "z"));
        c.setValue("list[1]", "Y"); // bracket: replace an existing element
        assertEquals("Y", c.getString("list[1]"));
        c.setValue("list.2", "Z");  // dotted-numeric into an array also addresses an element
        assertEquals("Z", c.getString("list[2]"));
    }

    @Test
    void bracketIndexNeverGrowsAnArray() {
        final Config c = new Config();
        c.setValue("list", Arrays.asList("x"));
        assertThrows(IllegalArgumentException.class, () -> c.setValue("list[5]", "v")); // out of bounds, no growth
    }

    @Test
    void aDottedKeyPathThroughAnArrayIntermediateFailsFast() {
        final Config c = new Config();
        c.setValue("a", Arrays.asList("x", "y")); // 'a' is an array
        // A key path running THROUGH an array intermediate throws, rather than silently replacing the array.
        assertThrows(IllegalArgumentException.class, () -> c.setValue("a.b", 1));
    }

    @Test
    void removeHandlesDottedAndBracket() {
        final Config c = new Config();
        c.setValue("a.b", 1);
        c.setValue("a.c", 2);
        assertTrue(c.removeValue("a.b"));
        assertFalse(c.contains("a.b"));
        assertTrue(c.contains("a.c"));

        c.setValue("list", Arrays.asList("x", "y", "z"));
        assertTrue(c.removeValue("list[1]"));
        assertEquals(Arrays.asList("x", "z"), c.getStringList("list"));
    }
}
