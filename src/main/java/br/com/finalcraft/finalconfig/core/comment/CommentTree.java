package br.com.finalcraft.finalconfig.core.comment;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Format-agnostic comment overlay keyed by dotted path (decision #3). Each path may carry a block
 * comment and a side comment, each tagged with provenance — {@code seeded=false} means authoritative
 * (parsed from the file at load, or set explicitly via {@link #setComment}); {@code seeded=true} means
 * code-seeded via {@code getOrSetDefaultValue(path, def, comment)}.
 *
 * <p>This is the minimal phase-01 surface (the seam that {@code Config} needs): the load parser and the
 * save-time reconciliation/emitter are fleshed out in phase 03. Comment text is stored WITHOUT the
 * {@code #} prefix; prefixing is an emit-time concern.
 */
public final class CommentTree {

    private static final class Entry {
        String block;
        boolean blockSeeded;
        String side;
        boolean sideSeeded;
    }

    private final Map<String, Entry> byPath = new LinkedHashMap<>();

    private Entry entry(final String path) {
        return byPath.computeIfAbsent(path == null ? "" : path, k -> new Entry());
    }

    // ---- load (authoritative, from file) ----

    public void putFileComment(final String path, final String comment, final CommentType type) {
        final Entry e = entry(path);
        if (type == CommentType.SIDE) {
            e.side = comment;
            e.sideSeeded = false;
        } else {
            e.block = comment;
            e.blockSeeded = false;
        }
    }

    // ---- explicit API (authoritative) ----

    public void setComment(final String path, final String comment, final CommentType type) {
        if (comment == null) {
            removeComment(path, type);
            return;
        }
        final Entry e = entry(path);
        if (type == CommentType.SIDE) {
            e.side = comment;
            e.sideSeeded = false;
        } else {
            e.block = comment;
            e.blockSeeded = false;
        }
    }

    public String getComment(final String path, final CommentType type) {
        final Entry e = byPath.get(path == null ? "" : path);
        if (e == null) {
            return null;
        }
        return type == CommentType.SIDE ? e.side : e.block;
    }

    public void removeComment(final String path, final CommentType type) {
        final Entry e = byPath.get(path == null ? "" : path);
        if (e == null) {
            return;
        }
        if (type == CommentType.SIDE) {
            e.side = null;
        } else {
            e.block = null;
        }
    }

    // ---- seed (decision #1: only if no authoritative comment exists) ----

    /** Seed a BLOCK comment only when the path has no authoritative (file/explicit) comment yet. */
    public void seedComment(final String path, final String comment) {
        final Entry e = entry(path);
        if (e.block != null && !e.blockSeeded) {
            return; // user/file comment wins
        }
        e.block = comment;
        e.blockSeeded = true;
    }

    /** True when the path carries an authoritative (file/explicit, non-seeded) block comment. */
    public boolean hasUserComment(final String path) {
        final Entry e = byPath.get(path == null ? "" : path);
        return e != null && e.block != null && !e.blockSeeded;
    }

    // ---- lifecycle ----

    /**
     * Drop the comment for {@code path} and every descendant. Called whenever a path's data subtree is
     * structurally replaced or removed (spec 01 §5.1 rule 4) so a comment never outlives its data.
     */
    public void removeSubtree(final String path) {
        if (path == null || path.isEmpty()) {
            byPath.clear();
            return;
        }
        final String prefix = path + ".";
        final Iterator<String> it = byPath.keySet().iterator();
        while (it.hasNext()) {
            final String key = it.next();
            if (key.equals(path) || key.startsWith(prefix)) {
                it.remove();
            }
        }
    }

    public boolean isEmpty() {
        return byPath.isEmpty();
    }
}
