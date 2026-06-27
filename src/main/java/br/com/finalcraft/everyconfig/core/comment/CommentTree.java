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

    public boolean isEmpty() {
        return byPath.isEmpty();
    }
}
