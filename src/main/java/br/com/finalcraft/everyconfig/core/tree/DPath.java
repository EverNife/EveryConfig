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
}
