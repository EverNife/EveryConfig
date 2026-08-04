package br.com.finalcraft.everyconfig.core.comment;

/**
 * The in-memory rendering policy for comment spacing: how many blank lines float above a COMMENTED entry,
 * and how deep that reaches. Immutable. Like a key-order pin it is a RENDERING policy, not file data: it
 * is held by the config and stamped onto the comment snapshot at encode time, never written to the file.
 *
 * <p>Two rules are fixed rather than configurable: the floor reaches only an entry that carries a block
 * comment, and never the first entry emitted in its block (there is already a boundary there).
 */
public final class CommentStyle {

    /** Spacing off — the emitted layout keeps whatever vertical shape the file already carried. */
    public static final CommentStyle NONE = new CommentStyle(0, 1);

    private final int blankLines;
    private final int maxDepth;

    private CommentStyle(final int blankLines, final int maxDepth) {
        this.blankLines = Math.max(0, blankLines);
        this.maxDepth = Math.max(0, maxDepth);
    }

    /** {@code blankLines} above every commented entry down to {@code maxDepth} (1 = root keys only). */
    public static CommentStyle of(final int blankLines, final int maxDepth) {
        return new CommentStyle(blankLines, maxDepth);
    }

    /** Blank lines floated above a commented entry; 0 turns the policy off. */
    public int blankLines() {
        return blankLines;
    }

    /** Deepest level the policy reaches; a root key is depth 1. */
    public int maxDepth() {
        return maxDepth;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CommentStyle)) {
            return false;
        }
        final CommentStyle other = (CommentStyle) o;
        return blankLines == other.blankLines && maxDepth == other.maxDepth;
    }

    @Override
    public int hashCode() {
        return blankLines * 31 + maxDepth;
    }

    @Override
    public String toString() {
        return "CommentStyle{blankLines=" + blankLines + ", maxDepth=" + maxDepth + '}';
    }
}
