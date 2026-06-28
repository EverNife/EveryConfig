package br.com.finalcraft.everyconfig.core.tree;

import java.util.ArrayList;
import java.util.List;

/**
 * Dotted-path utilities (the "D" is for the dotted-path grammar of the dynamic API; named to avoid
 * clashing with {@link java.nio.file.Path}). The empty string and {@code null} both denote the root.
 *
 * <p>The separator (a dot by default) splits a path into segments. A key that legitimately contains the
 * separator is escaped with a backslash: {@code a\.b} is the single key {@code "a.b"}, and {@code \\} is a
 * literal backslash. A path string is therefore the <em>escaped</em> form; {@link #split}/{@link #parse}/
 * {@link #leaf} return the <em>literal</em> key names, and {@link #joinSegment}/{@link #escapeSegment}
 * build the escaped form back from a literal key. Escaping is a no-op for a key free of the separator and
 * backslash, so an ordinary dotted path behaves exactly as before.
 */
public final class DPath {

    private DPath() {
    }

    /** {@code "a.b.c"} → {@code ["a","b","c"]}; an escaped separator stays in its segment
     *  ({@code "a\.b.c"} → {@code ["a.b","c"]}); {@code ""}/{@code null} → empty array. */
    public static String[] split(final String path, final char sep) {
        if (isRoot(path)) {
            return new String[0];
        }
        final List<String> out = new ArrayList<>();
        final StringBuilder cur = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            final char c = path.charAt(i);
            if (c == '\\' && i + 1 < path.length()) {
                cur.append(path.charAt(i + 1)); // the escaped character is literal
                i++;
            } else if (c == sep) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    public static boolean isRoot(final String path) {
        return path == null || path.isEmpty();
    }

    /** {@code "a.b.c"} → {@code "a.b"}; {@code "a"} → {@code ""}. Splits on the last UNescaped separator,
     *  so the returned parent keeps its escaped form. */
    public static String parent(final String path, final char sep) {
        if (isRoot(path)) {
            return "";
        }
        final int i = lastUnescapedSep(path, sep);
        return i < 0 ? "" : path.substring(0, i);
    }

    /** {@code "a.b.c"} → {@code "c"}; {@code "a\.b"} → the literal key {@code "a.b"}. */
    public static String leaf(final String path, final char sep) {
        final String[] segs = split(path, sep);
        return segs.length == 0 ? "" : segs[segs.length - 1];
    }

    /** Join two already-escaped path strings (no re-escaping — both sides are path-form, not raw keys). */
    public static String join(final String base, final String sub, final char sep) {
        if (isRoot(base)) {
            return sub == null ? "" : sub;
        }
        if (isRoot(sub)) {
            return base;
        }
        return base + sep + sub;
    }

    /** Append a LITERAL key to an escaped path, escaping the key so a separator inside it stays part of the
     *  key. {@code joinSegment("a", "b.c", '.')} → {@code "a.b\.c"}. */
    public static String joinSegment(final String base, final String literalKey, final char sep) {
        final String esc = escapeSegment(literalKey, sep);
        return isRoot(base) ? esc : base + sep + esc;
    }

    /** Escape a literal key for embedding in a path: a backslash and the separator each gain a backslash. */
    public static String escapeSegment(final String literalKey, final char sep) {
        if (literalKey.indexOf('\\') < 0 && literalKey.indexOf(sep) < 0) {
            return literalKey; // common case: nothing to escape, returned untouched
        }
        final StringBuilder sb = new StringBuilder(literalKey.length() + 4);
        for (int i = 0; i < literalKey.length(); i++) {
            final char c = literalKey.charAt(i);
            if (c == '\\' || c == sep) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Index of the last separator that is NOT backslash-escaped, or -1. */
    private static int lastUnescapedSep(final String path, final char sep) {
        int last = -1;
        for (int i = 0; i < path.length(); i++) {
            final char c = path.charAt(i);
            if (c == '\\') {
                i++; // skip the escaped character
            } else if (c == sep) {
                last = i;
            }
        }
        return last;
    }

    /** True for a non-negative integer literal, which selects an {@code ArrayNode} element. */
    public static boolean isIndex(final String seg) {
        if (seg == null || seg.isEmpty()) {
            return false;
        }
        for (int i = 0; i < seg.length(); i++) {
            if (!Character.isDigit(seg.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** True when {@code path} uses the bracket-index grammar somewhere, so the typed {@link #parse} is
     *  needed instead of the plain dotted {@link #split}. */
    public static boolean hasBracket(final String path) {
        return path != null && path.indexOf('[') >= 0;
    }

    /**
     * A single parsed path segment: either a dotted key (which may itself be a bare numeric token,
     * resolved against the live node's type) or an explicit bracket index {@code [n]} that forces
     * array-element semantics and may be negative (counting from the end).
     */
    public static final class Seg {
        /** The key text for a key segment; {@code null} for a bracket-index segment. */
        public final String key;
        /** True when this is an explicit {@code [n]} bracket index (forces array semantics). */
        public final boolean index;
        /** The parsed int for a bracket-index segment (negative counts from the end); 0 otherwise. */
        public final int indexValue;

        private Seg(final String key, final boolean index, final int indexValue) {
            this.key = key;
            this.index = index;
            this.indexValue = indexValue;
        }

        static Seg ofKey(final String key) {
            return new Seg(key, false, 0);
        }

        static Seg ofIndex(final int value) {
            return new Seg(null, true, value);
        }
    }

    /**
     * Tokenize a path into typed {@link Seg}s. A path with no {@code '['} segments tokenizes exactly like
     * {@link #split} (each piece a key segment), so existing dotted paths behave identically; bracket
     * runs ({@code list[0]}, {@code m[1][2]}, {@code list[-1].name}) become explicit index segments.
     */
    public static List<Seg> parse(final String path, final char sep) {
        final List<Seg> out = new ArrayList<>();
        if (isRoot(path)) {
            return out;
        }
        if (path.indexOf('[') < 0) {
            for (final String s : split(path, sep)) {
                out.add(Seg.ofKey(s));
            }
            return out;
        }
        final StringBuilder buf = new StringBuilder();
        int i = 0;
        final int n = path.length();
        while (i < n) {
            final char c = path.charAt(i);
            if (c == '\\' && i + 1 < n) {
                buf.append(path.charAt(i + 1)); // the escaped character is a literal key character
                i += 2;
            } else if (c == sep) {
                out.add(Seg.ofKey(buf.toString()));
                buf.setLength(0);
                i++;
            } else if (c == '[') {
                final int close = path.indexOf(']', i + 1);
                final Integer idx = close > i ? parseSignedInt(path.substring(i + 1, close)) : null;
                if (idx != null) {
                    if (buf.length() > 0) {
                        out.add(Seg.ofKey(buf.toString()));
                        buf.setLength(0);
                    }
                    out.add(Seg.ofIndex(idx));
                    i = close + 1;
                    if (i < n && path.charAt(i) == sep) {
                        i++; // a separator joining "]" to the next key is redundant
                    }
                } else {
                    buf.append(c); // a '[' that is not a valid [int] is an ordinary key character
                    i++;
                }
            } else {
                buf.append(c);
                i++;
            }
        }
        if (buf.length() > 0) {
            out.add(Seg.ofKey(buf.toString()));
        }
        return out;
    }

    /** Parse a possibly-negative int literal, returning null when {@code s} is not one (or overflows). */
    private static Integer parseSignedInt(final String s) {
        if (s.isEmpty()) {
            return null;
        }
        int start = 0;
        if (s.charAt(0) == '-') {
            if (s.length() == 1) {
                return null;
            }
            start = 1;
        }
        for (int i = start; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.valueOf(Integer.parseInt(s));
        } catch (final NumberFormatException overflow) {
            return null;
        }
    }
}
