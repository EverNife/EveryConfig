package br.com.finalcraft.everyconfig.core.tree;

import java.util.ArrayList;
import java.util.List;

/**
 * Dotted-path utilities (the "D" is for the dotted-path grammar of the dynamic API; named to avoid
 * clashing with {@link java.nio.file.Path}). The empty string and {@code null} both denote the root.
 */
public final class DPath {

    private DPath() {
    }

    /** {@code "a.b.c"} → {@code ["a","b","c"]}; {@code ""}/{@code null} → empty array. */
    public static String[] split(final String path, final char sep) {
        if (isRoot(path)) {
            return new String[0];
        }
        final List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == sep) {
                out.add(path.substring(start, i));
                start = i + 1;
            }
        }
        out.add(path.substring(start));
        return out.toArray(new String[0]);
    }

    public static boolean isRoot(final String path) {
        return path == null || path.isEmpty();
    }

    /** {@code "a.b.c"} → {@code "a.b"}; {@code "a"} → {@code ""}. */
    public static String parent(final String path, final char sep) {
        if (isRoot(path)) {
            return "";
        }
        final int i = path.lastIndexOf(sep);
        return i < 0 ? "" : path.substring(0, i);
    }

    /** {@code "a.b.c"} → {@code "c"}; {@code "a"} → {@code "a"}. */
    public static String leaf(final String path, final char sep) {
        if (isRoot(path)) {
            return "";
        }
        final int i = path.lastIndexOf(sep);
        return i < 0 ? path : path.substring(i + 1);
    }

    public static String join(final String base, final String sub, final char sep) {
        if (isRoot(base)) {
            return sub == null ? "" : sub;
        }
        if (isRoot(sub)) {
            return base;
        }
        return base + sep + sub;
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
            if (c == sep) {
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
