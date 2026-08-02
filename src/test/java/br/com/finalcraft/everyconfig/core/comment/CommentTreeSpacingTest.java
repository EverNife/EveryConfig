package br.com.finalcraft.everyconfig.core.comment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The two spacing sources a comment overlay carries: the per-path count stored on it (from the file or
 * from a leading-blank-line directive) and the rendering policy floor resolved on top of it.
 */
class CommentTreeSpacingTest {

    @Test
    void leadingBlankLinesInAnApiWriteAreADirective() {
        final CommentTree tree = new CommentTree();
        tree.setComment("a", "\n\nSection two", CommentType.BLOCK);

        assertEquals("Section two", tree.getComment("a", CommentType.BLOCK));
        assertEquals(2, tree.getBlankLinesBefore("a"));
    }

    @Test
    void anApiWriteWithoutADirectiveLeavesTheStoredSpacingAlone() {
        final CommentTree tree = new CommentTree();
        tree.setBlankLinesBefore("a", 3);          // hand-typed separation coming from the file
        tree.setComment("a", "Core settings.", CommentType.BLOCK);

        assertEquals(3, tree.getBlankLinesBefore("a"));
        assertEquals("Core settings.", tree.getComment("a", CommentType.BLOCK));
    }

    @Test
    void aDirectiveRewritesSpacingThatIsAlreadyThere() {
        final CommentTree tree = new CommentTree();
        tree.setBlankLinesBefore("a", 3);
        tree.setComment("a", "\nCore settings.", CommentType.BLOCK);

        assertEquals(1, tree.getBlankLinesBefore("a"));
    }

    @Test
    void anAllEmptyTextKeepsItsHistoricalMeaning() {
        final CommentTree tree = new CommentTree();

        tree.setComment("a", "", CommentType.BLOCK);
        assertEquals("", tree.getComment("a", CommentType.BLOCK));
        assertEquals(0, tree.getBlankLinesBefore("a"));

        tree.setComment("b", "\n", CommentType.BLOCK);
        assertEquals("\n", tree.getComment("b", CommentType.BLOCK));
        assertEquals(0, tree.getBlankLinesBefore("b"));
    }

    @Test
    void aSideCommentIsNeverADirective() {
        final CommentTree tree = new CommentTree();
        tree.setComment("a", "\nnot a directive", CommentType.SIDE);

        assertEquals("\nnot a directive", tree.getComment("a", CommentType.SIDE));
        assertEquals(0, tree.getBlankLinesBefore("a"));
    }

    @Test
    void theParserPathStoresTheTextVerbatim() {
        final CommentTree tree = new CommentTree();
        // a cushioned block ("#" / "# Section two" / "#") reads back with a leading empty line
        tree.setBlankLinesBefore("a", 0);
        tree.putFileComment("a", "\nSection two\n", CommentType.BLOCK);

        assertEquals("\nSection two\n", tree.getComment("a", CommentType.BLOCK));
        assertEquals(0, tree.getBlankLinesBefore("a"));
    }

    @Test
    void thePolicyOnlyLiftsACommentedEntryThatOpensNoBlock() {
        final CommentTree tree = new CommentTree();
        tree.setStyle(CommentStyle.of(1, 1));
        tree.setComment("commented", "doc", CommentType.BLOCK);

        assertEquals(1, tree.effectiveBlankLinesBefore("commented", 1, false));
        assertEquals(0, tree.effectiveBlankLinesBefore("commented", 1, true));   // first in its block
        assertEquals(0, tree.effectiveBlankLinesBefore("bare", 1, false));       // no block comment
    }

    @Test
    void thePolicyStopsAtItsMaxDepth() {
        final CommentTree tree = new CommentTree();
        tree.setStyle(CommentStyle.of(1, 2));
        tree.setComment("a", "doc", CommentType.BLOCK);

        assertEquals(1, tree.effectiveBlankLinesBefore("a", 2, false));
        assertEquals(0, tree.effectiveBlankLinesBefore("a", 3, false));
    }

    @Test
    void theEffectiveCountIsTheHigherOfStoredAndPolicy() {
        final CommentTree tree = new CommentTree();
        tree.setStyle(CommentStyle.of(1, 1));

        tree.setComment("wide", "doc", CommentType.BLOCK);
        tree.setBlankLinesBefore("wide", 3);
        assertEquals(3, tree.effectiveBlankLinesBefore("wide", 1, false));       // policy never tightens

        tree.setComment("deep", "\n\ndoc", CommentType.BLOCK);                   // directive out of reach
        assertEquals(2, tree.effectiveBlankLinesBefore("deep", 5, false));
    }

    @Test
    void aTreeWithoutAPolicyEmitsWhatItStores() {
        final CommentTree tree = new CommentTree();
        tree.setComment("a", "doc", CommentType.BLOCK);
        tree.setBlankLinesBefore("a", 2);

        assertSame(CommentStyle.NONE, tree.getStyle());
        assertEquals(2, tree.effectiveBlankLinesBefore("a", 1, false));
        assertEquals(0, tree.effectiveBlankLinesBefore("b", 1, false));
    }

    @Test
    void theStyleRidesAlongOnACopyAndFallsBackToNone() {
        final CommentTree tree = new CommentTree();
        tree.setStyle(CommentStyle.of(2, 3));
        assertEquals(CommentStyle.of(2, 3), tree.copy().getStyle());

        tree.setStyle(null);
        assertSame(CommentStyle.NONE, tree.getStyle());
    }

    @Test
    void aStyleClampsNegativesAndComparesByValue() {
        assertEquals(0, CommentStyle.of(-5, -2).blankLines());
        assertEquals(0, CommentStyle.of(-5, -2).maxDepth());

        assertEquals(CommentStyle.NONE, CommentStyle.of(0, 1));
        assertEquals(CommentStyle.of(1, 2).hashCode(), CommentStyle.of(1, 2).hashCode());
        assertNotEquals(CommentStyle.of(1, 2), CommentStyle.of(2, 1));
        assertEquals("CommentStyle{blankLines=1, maxDepth=2}", CommentStyle.of(1, 2).toString());
    }
}
