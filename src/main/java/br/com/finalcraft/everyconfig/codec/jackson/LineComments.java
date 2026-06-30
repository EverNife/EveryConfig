package br.com.finalcraft.everyconfig.codec.jackson;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared line-based comment helpers for the comment-aware codecs. Each codec passes its own line-comment
 * marker ({@code #} for YAML/TOML, {@code //} for JSONC); the prefix / strip / block-extraction logic is
 * otherwise identical, so it lives here once instead of being copied into every codec where it could drift.
 *
 * <p>How each codec finds the file <em>header</em> still differs by format and stays in the codec: YAML and
 * TOML use {@link #headerBoundary} (the blank line before the first key), while JSONC uses the opening brace.
 */
final class LineComments {

    private LineComments() {
    }

    /** Prefix a stored (prefix-less) comment line with {@code marker}; an empty line becomes the bare marker,
     *  so a blank line inside a header/footer round-trips instead of reading as the separator. Trailing
     *  whitespace is dropped here so the emitted comment is already in the canonical form the parser would
     *  read it back as (it captures lines via {@code trim()}); this keeps write -> read -> write stable. */
    static String prefix(final String marker, final String line) {
        final String trimmed = rstrip(line);
        return trimmed.isEmpty() ? marker : marker + " " + trimmed;
    }

    /** Drop trailing whitespace from a single line (no Java-8 String API does this). */
    private static String rstrip(final String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    /** Strip a leading {@code marker} (and one following space) from a single comment line. */
    static String strip(final String marker, final String line) {
        String s = line;
        if (s.startsWith(marker)) {
            s = s.substring(marker.length());
        }
        if (s.startsWith(" ")) {
            s = s.substring(1);
        }
        return s;
    }

    /** Drop leading/trailing blank lines from {@code raw}, then strip {@code marker} from each remaining line
     *  — turning a captured comment block into its stored, prefix-less lines. */
    static List<String> extractBlockLines(final String marker, final List<String> raw) {
        int start = 0;
        int end = raw.size();
        while (start < end && raw.get(start).isEmpty()) {
            start++;
        }
        while (end > start && raw.get(end - 1).isEmpty()) {
            end--;
        }
        final List<String> out = new ArrayList<>();
        for (int i = start; i < end; i++) {
            out.add(strip(marker, raw.get(i)));
        }
        return out;
    }

    /**
     * Index in {@code pending} of the blank line that separates a file header from the first key's own block
     * — the first blank line that follows at least one comment line — or -1 when the leading comments run
     * straight into the key (so there is no header). Used by the blank-line formats (YAML/TOML).
     */
    static int headerBoundary(final List<String> pending) {
        boolean sawComment = false;
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).isEmpty()) {
                if (sawComment) {
                    return i;
                }
            } else {
                sawComment = true;
            }
        }
        return -1;
    }
}
