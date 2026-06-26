package br.com.finalcraft.finalconfig.spike.yaml;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * SPIKE: a text-based YAML comment parser. Reads YAML line by line, tracks the indent-to-path stack,
 * and attaches block comments (the {@code #...} lines above a key) and side comments (the {@code # ...}
 * trailing a value) into a {@link CommentTree}. It does NOT parse data — data is parsed by Jackson into
 * the {@code ObjectNode}; this only recovers comments and their paths.
 *
 * <p>Adapted from Simple-YAML's {@code YamlCommentParser}/{@code YamlCommentReader}, simplified to the
 * common block-style cases (nested mappings, scalar + side comments). List-item comments are out of
 * scope for the spike. Promoted/refined in phase 03.
 */
public final class YamlCommentParser {

    private static final class Frame {
        final int indent;
        final String key;

        Frame(final int indent, final String key) {
            this.indent = indent;
            this.key = key;
        }
    }

    public CommentTree parse(final String yaml) {
        final CommentTree tree = new CommentTree();
        final Deque<Frame> stack = new ArrayDeque<>();
        final List<String> pendingBlock = new ArrayList<>();

        for (final String raw : yaml.split("\n", -1)) {
            final String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            final String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                pendingBlock.add("");
                continue;
            }
            if (trimmed.charAt(0) == '#') {
                pendingBlock.add(trimmed);
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                pendingBlock.clear(); // list element: not tracked in the spike
                continue;
            }

            final int colon = keyColon(trimmed);
            if (colon < 0) {
                pendingBlock.clear();
                continue;
            }

            final int indent = leadingSpaces(line);
            final String key = unquote(trimmed.substring(0, colon).trim());

            while (!stack.isEmpty() && stack.peek().indent >= indent) {
                stack.pop();
            }
            final String path = pathOf(stack, key);
            stack.push(new Frame(indent, key));

            if (!pendingBlock.isEmpty()) {
                final String block = joinTrim(pendingBlock);
                if (!block.isEmpty()) {
                    tree.setBlockRaw(path, block);
                }
                pendingBlock.clear();
            }

            final String afterColon = trimmed.substring(colon + 1);
            final int hash = sideHash(afterColon);
            if (hash >= 0) {
                tree.setSideRaw(path, " " + afterColon.substring(hash).trim());
            }
        }
        return tree;
    }

    // ---- helpers ----

    private static int leadingSpaces(final String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /** Index of the ':' that separates a key from its value (end-of-line or followed by a space). */
    private static int keyColon(final String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ':' && (i == s.length() - 1 || s.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    /** First {@code '#'} starting a side comment (preceded by a space, not inside quotes), or -1. */
    private static int sideHash(final String after) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < after.length(); i++) {
            final char c = after.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble && (i == 0 || after.charAt(i - 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(final String key) {
        if (key.length() >= 2) {
            final char a = key.charAt(0);
            final char b = key.charAt(key.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return key.substring(1, key.length() - 1);
            }
        }
        return key;
    }

    private static String pathOf(final Deque<Frame> ancestors, final String key) {
        final StringBuilder sb = new StringBuilder();
        final Iterator<Frame> it = ancestors.descendingIterator(); // outermost -> innermost
        while (it.hasNext()) {
            sb.append(it.next().key).append('.');
        }
        return sb.append(key).toString();
    }

    /** Join lines with '\n', dropping leading and trailing blank lines. */
    private static String joinTrim(final List<String> lines) {
        int start = 0;
        int end = lines.size();
        while (start < end && lines.get(start).isEmpty()) {
            start++;
        }
        while (end > start && lines.get(end - 1).isEmpty()) {
            end--;
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }
}
