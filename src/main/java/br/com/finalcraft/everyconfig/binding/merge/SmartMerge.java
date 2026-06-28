package br.com.finalcraft.everyconfig.binding.merge;
import br.com.finalcraft.everyconfig.binding.BindOptions;
import br.com.finalcraft.everyconfig.binding.schema.Schema;
import br.com.finalcraft.everyconfig.core.comment.CommentTree;
import br.com.finalcraft.everyconfig.core.comment.CommentType;
import br.com.finalcraft.everyconfig.core.tree.DPath;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Merges a POJO-derived tree INTO the canonical tree, never replacing it. The POJO is the source of
 * truth for the keys it declares; everything else the canonical tree already holds survives. Nested
 * objects merge recursively; arrays and scalars are taken whole from the POJO (a list has no stable
 * identity to merge element-by-element). A key the canonical tree has but the POJO does not is handled
 * by the obsolete policy (kept, removed, or kept-and-deprecated) and only at a level the schema fully
 * owns — never inside a free-form map.
 */
public final class SmartMerge {

    private static final String DEPRECATION_MARKER =
            "DEPRECATED - this key is no longer used by the application and can be removed.";

    private SmartMerge() {
    }

    /**
     * @param comments    the overlay to stamp under {@code COMMENT_OUT} (unused for the other policies)
     * @param pathPrefix  dotted path of {@code canonical} within the whole tree ("" at the root), used to
     *                    address an obsolete key's comment
     * @param lossless    whether the codec round-trips comments losslessly; {@code COMMENT_OUT} degrades to
     *                    keep-only when false
     * @param sep         the path separator joining {@code pathPrefix} to a key
     */
    public static void mergeInto(final ObjectNode canonical, final ObjectNode candidate,
                          final Schema schema, final BindOptions options,
                          final CommentTree comments, final String pathPrefix,
                          final boolean lossless, final char sep) {
        final Iterator<String> names = candidate.fieldNames();
        while (names.hasNext()) {
            final String key = names.next();
            final JsonNode cand = candidate.get(key);
            final JsonNode canon = canonical.get(key);
            if (canon == null) {
                canonical.set(key, cand); // missing from the file -> seed from the POJO, appended
            } else if (canon.isObject() && cand.isObject()) {
                mergeInto((ObjectNode) canon, (ObjectNode) cand, schema.child(key), options,
                        comments, childPath(pathPrefix, key, sep), lossless, sep);
            } else {
                canonical.set(key, cand); // arrays, scalars, and mixed kinds -> the POJO value wins
            }
        }

        final BindOptions.ObsoletePolicy policy = options.obsoletePolicy();
        // A closed schema that declares no keys cannot legitimately own a node's contents (e.g. a
        // polymorphic node typed to an abstract base with no own fields), so treating its children as
        // obsolete would strip data the schema cannot account for. Only act when the schema declares keys.
        if ((policy == BindOptions.ObsoletePolicy.REMOVE || policy == BindOptions.ObsoletePolicy.COMMENT_OUT)
                && schema.isClosed() && !schema.declaredKeys().isEmpty()) {
            final List<String> obsolete = new ArrayList<>();
            final Iterator<String> canonNames = canonical.fieldNames();
            while (canonNames.hasNext()) {
                final String key = canonNames.next();
                if (!candidate.has(key) && schema.isObsolete(key)) {
                    obsolete.add(key);
                }
            }
            for (final String key : obsolete) {
                if (policy == BindOptions.ObsoletePolicy.REMOVE) {
                    canonical.remove(key);
                } else if (lossless && comments != null) {
                    // Keep the data, stamp an authoritative deprecation marker (LOSSLESS codecs only).
                    comments.setComment(childPath(pathPrefix, key, sep), DEPRECATION_MARKER, CommentType.BLOCK);
                }
            }
        }
    }

    private static String childPath(final String prefix, final String key, final char sep) {
        return DPath.joinSegment(prefix, key, sep);
    }
}
