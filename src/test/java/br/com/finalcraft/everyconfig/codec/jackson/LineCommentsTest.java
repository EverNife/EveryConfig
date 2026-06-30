package br.com.finalcraft.everyconfig.codec.jackson;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The shared line-comment helpers used by the comment-aware codecs (marker-parameterized). */
class LineCommentsTest {

    @Test
    void prefixAndStripAreMarkerAware() {
        assertEquals("# hi", LineComments.prefix("#", "hi"));
        assertEquals("#", LineComments.prefix("#", ""));   // an empty line becomes the bare marker
        assertEquals("// hi", LineComments.prefix("//", "hi"));
        assertEquals("//", LineComments.prefix("//", ""));

        assertEquals("hi", LineComments.strip("#", "# hi"));
        assertEquals("", LineComments.strip("#", "#"));
        assertEquals("hi", LineComments.strip("//", "// hi"));
        assertEquals("", LineComments.strip("//", "//"));
        assertEquals("  indented", LineComments.strip("#", "#   indented")); // strips the marker + ONE space
    }

    @Test
    void prefixDropsTrailingWhitespace() {
        // emit in canonical form (the parser reads lines back trimmed), so write -> read -> write is stable
        assertEquals("# hi", LineComments.prefix("#", "hi   "));                 // trailing dropped
        assertEquals("//   indented", LineComments.prefix("//", "  indented  ")); // leading kept, trailing dropped
        assertEquals("#", LineComments.prefix("#", "    "));                     // all-blank -> bare marker
    }

    @Test
    void extractBlockLinesDropsOuterBlanksAndStripsMarkers() {
        assertEquals(Arrays.asList("a", "", "b"),
                LineComments.extractBlockLines("#", Arrays.asList("", "# a", "#", "# b", "")));
        assertEquals(Collections.emptyList(),
                LineComments.extractBlockLines("#", Arrays.asList("", "")));
    }

    @Test
    void headerBoundaryIsTheFirstBlankAfterAComment() {
        assertEquals(2, LineComments.headerBoundary(Arrays.asList("# h1", "# h2", "", "# key comment")));
        assertEquals(1, LineComments.headerBoundary(Arrays.asList("# only", "")));
        assertEquals(-1, LineComments.headerBoundary(Arrays.asList("# c1", "# c2")));   // runs into the key
        assertEquals(-1, LineComments.headerBoundary(Arrays.asList("", "")));           // no comment -> no header
        assertEquals(-1, LineComments.headerBoundary(Collections.<String>emptyList()));
    }
}
