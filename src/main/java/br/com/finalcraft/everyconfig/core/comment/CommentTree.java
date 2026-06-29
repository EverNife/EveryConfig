package br.com.finalcraft.everyconfig.core.comment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Format-agnostic comment overlay keyed by dotted path. Each path may carry a block comment and a side
 * comment. Two write modes are offered: {@link #setComment} always overwrites, and
 * {@link #setDefaultComment} writes only when the path has no comment yet.
 *
 * <p>Besides comments, the overlay carries the vertical layout that the data tree cannot: the count of
 * blank lines kept above each key, and the file's header (above the first key) and footer (below the
 * last key) comment blocks. All of it is reconciled and re-emitted on save so a round-trip preserves the
 * file's shape, not just its data.
 *
 * <p>Comment text is stored without the {@code #} prefix; prefixing happens when the document is
 * written.
 */
public final class CommentTree {

    private static final class Entry {
        String block;
        String side;
        int blankLinesBefore;
    }

    private final Map<String, Entry> byPath = new LinkedHashMap<>();

    /** File header / footer comment blocks (prefix-less lines), or null when absent. */
    private List<String> header;
    private List<String> footer;

    private Entry entry(final String path) {
        return byPath.computeIfAbsent(path == null ? "" : path, k -> new Entry());
    }

    // ---- load (from file) ----

    public void putFileComment(final String path, final String comment, final CommentType type) {
        setComment(path, comment, type);
    }

    // ---- write: always overwrite ----

    public void setComment(final String path, final String comment, final CommentType type) {
        if (comment == null) {
            removeComment(path, type);
            return;
        }
        final Entry e = entry(path);
        if (type == CommentType.SIDE) {
            e.side = comment;
        } else {
            e.block = comment;
        }
    }

    // ---- write: only when nothing is there yet ----

    /** Write the comment only when the path currently carries no comment of that type. */
    public void setDefaultComment(final String path, final String comment, final CommentType type) {
        if (comment != null && getComment(path, type) == null) {
            setComment(path, comment, type);
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

    // ---- vertical layout (blank lines kept above a key) ----

    public void setBlankLinesBefore(final String path, final int count) {
        entry(path).blankLinesBefore = Math.max(0, count);
    }

    public int getBlankLinesBefore(final String path) {
        final Entry e = byPath.get(path == null ? "" : path);
        return e == null ? 0 : e.blankLinesBefore;
    }

    // ---- header / footer ----

    public void setHeader(final List<String> lines) {
        this.header = (lines == null || lines.isEmpty()) ? null : new ArrayList<>(lines);
    }

    public List<String> getHeader() {
        return header == null ? Collections.<String>emptyList() : Collections.unmodifiableList(header);
    }

    public void setFooter(final List<String> lines) {
        this.footer = (lines == null || lines.isEmpty()) ? null : new ArrayList<>(lines);
    }

    public List<String> getFooter() {
        return footer == null ? Collections.<String>emptyList() : Collections.unmodifiableList(footer);
    }

    // ---- lifecycle ----

    /**
     * Drop the comment for {@code path} and every descendant. Called whenever a path's data subtree is
     * structurally replaced or removed, so a comment never outlives the data it described.
     */
    public void removeSubtree(final String path) {
        if (path == null || path.isEmpty()) {
            byPath.clear();
            header = null;
            footer = null;
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

    /**
     * Remove the comment overlay for {@code path} and every descendant and return it as a relocatable
     * snapshot keyed by each entry's suffix relative to {@code path} ({@code ""} for the path itself).
     * Pairs with {@link #attachSubtree} to carry a key's whole documentation when its data subtree is
     * renamed or moved.
     */
    public Snapshot detachSubtree(final String path) {
        final Map<String, Entry> out = new LinkedHashMap<>();
        if (path == null || path.isEmpty()) {
            return new Snapshot(out);
        }
        final String prefix = path + ".";
        final Iterator<Map.Entry<String, Entry>> it = byPath.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, Entry> e = it.next();
            final String key = e.getKey();
            if (key.equals(path)) {
                out.put("", e.getValue());
                it.remove();
            } else if (key.startsWith(prefix)) {
                out.put(key.substring(prefix.length()), e.getValue());
                it.remove();
            }
        }
        return new Snapshot(out);
    }

    /**
     * Re-attach a {@link #detachSubtree} snapshot under {@code newPath}: every captured entry lands at
     * {@code newPath} plus its relative suffix, overwriting any comment already at a target path.
     */
    public void attachSubtree(final String newPath, final Snapshot snapshot) {
        if (newPath == null || newPath.isEmpty() || snapshot == null || snapshot.isEmpty()) {
            return;
        }
        for (final Map.Entry<String, Entry> e : snapshot.entries.entrySet()) {
            final String suffix = e.getKey();
            byPath.put(suffix.isEmpty() ? newPath : newPath + "." + suffix, e.getValue());
        }
    }

    public boolean isEmpty() {
        return byPath.isEmpty();
    }

    /**
     * A deep, independent copy: the per-path entries plus the header and footer are duplicated, so a later
     * mutation of either tree never leaks into the other. {@code Config.save()} emits from such a copy
     * (taken under its lock) so a concurrent unlocked mutation cannot corrupt the write.
     */
    public CommentTree copy() {
        final CommentTree out = new CommentTree();
        for (final Map.Entry<String, Entry> e : byPath.entrySet()) {
            final Entry src = e.getValue();
            final Entry dst = new Entry();
            dst.block = src.block;
            dst.side = src.side;
            dst.blankLinesBefore = src.blankLinesBefore;
            out.byPath.put(e.getKey(), dst);
        }
        out.header = header == null ? null : new ArrayList<>(header);
        out.footer = footer == null ? null : new ArrayList<>(footer);
        return out;
    }

    /** An opaque, relocatable capture of one path subtree's comment overlay (see {@link #detachSubtree}). */
    public static final class Snapshot {
        private final Map<String, Entry> entries;

        private Snapshot(final Map<String, Entry> entries) {
            this.entries = entries;
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }
}
