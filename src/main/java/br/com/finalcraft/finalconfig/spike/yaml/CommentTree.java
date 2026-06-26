package br.com.finalcraft.finalconfig.spike.yaml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SPIKE (de-risk prototype, not the production type): a format-agnostic comment overlay keyed by
 * dotted path. Each path may carry a raw block comment (the {@code #...} lines above a key) and a raw
 * side comment (the {@code # ...} trailing a value). Decoupled from the data tree — the values live in
 * the Jackson {@code ObjectNode}; this only holds comments.
 *
 * <p>Adapted from Simple-YAML's {@code KeyTree.Node} (block + side comment per node) but stripped of the
 * value reach-back that coupled it to the configuration. Promoted/refined in phase 03.
 */
public final class CommentTree {

    static final class Entry {
        String block;
        String side;
    }

    private final Map<String, Entry> byPath = new LinkedHashMap<>();

    private Entry entry(final String path) {
        return byPath.computeIfAbsent(path, k -> new Entry());
    }

    /** Store a raw block comment (lines including their {@code #} prefix), as parsed from a file. */
    public void setBlockRaw(final String path, final String rawBlock) {
        entry(path).block = rawBlock;
    }

    /** Store a raw side comment (e.g. {@code " # note"}), as parsed from a file. */
    public void setSideRaw(final String path, final String rawSide) {
        entry(path).side = rawSide;
    }

    /**
     * Seed a block comment from code (decision #1, seed half): adds a {@code "# "} prefix per line and
     * writes it ONLY if the path has no block comment yet, so a file/user comment always wins.
     */
    public void seedBlock(final String path, final String text) {
        final Entry existing = byPath.get(path);
        if (existing != null && existing.block != null) {
            return; // file/user comment wins
        }
        final StringBuilder sb = new StringBuilder();
        for (final String line : text.split("\n", -1)) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("# ").append(line);
        }
        entry(path).block = sb.toString();
    }

    public String block(final String path) {
        final Entry e = byPath.get(path);
        return e == null ? null : e.block;
    }

    public String side(final String path) {
        final Entry e = byPath.get(path);
        return e == null ? null : e.side;
    }

    public boolean hasBlock(final String path) {
        return block(path) != null;
    }
}
