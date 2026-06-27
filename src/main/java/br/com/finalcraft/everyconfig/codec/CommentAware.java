package br.com.finalcraft.everyconfig.codec;

import br.com.finalcraft.everyconfig.core.KeyOrder;
import br.com.finalcraft.everyconfig.core.comment.CommentTree;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Capability implemented by codecs whose format can carry comments
 * ({@link CommentFidelity#LOSSLESS} or {@link CommentFidelity#LOSSY}).
 *
 * <p>This is the emitter side of the structure/value split: the codec renders document STRUCTURE
 * (keys, indent, sections, comment lines, key order) itself and delegates ONLY leaf-value
 * serialization to its Jackson mapper via {@link #writeScalar}. The mapper's output is never
 * re-parsed, so a user-supplied mapper can restyle a leaf value but cannot break the layout.
 */
public interface CommentAware {

    /**
     * Extracts the file's comment overlay and key order from raw text in the same load pass as
     * {@link Codec#readTree}. Returns an empty (never null) overlay when the file genuinely carries
     * none. This is a TEXT pass — it does NOT go through the Jackson mapper.
     */
    CommentLoad readComments(String text);

    /**
     * Renders the reconciled tree to text. The {@code commentTree} is the result of comment
     * reconciliation (file comment wins; else seeded default; else none). {@code keyOrder} is the
     * file's key order with new keys appended. The emitter recurses structurally over the tree's
     * children and calls {@link #writeScalar} for each LEAF only.
     */
    String writeWithComments(JsonNode tree, CommentTree commentTree, KeyOrder keyOrder);

    /**
     * Serializes a single LEAF value to its inline textual form, using this codec's mapper. The
     * {@code leaf} is a genuine scalar (or an empty / flow collection); the emitter, which has already
     * decomposed the tree into nodes, never passes a container WITH children here — passing one is a
     * contract violation and MUST throw {@link CodecException}. A scalar normally yields a single line
     * (e.g. a date token, an empty list {@code []}); the one legitimate multi-line case is a multi-line
     * STRING value, whose embedded newlines the structure emitter re-indents.
     */
    String writeScalar(Object leaf);

    /** Carrier for the load-time pair the reconciler needs (besides the data tree). */
    final class CommentLoad {

        public final CommentTree comments; // may be empty, never null
        public final KeyOrder keyOrder;

        public CommentLoad(final CommentTree comments, final KeyOrder keyOrder) {
            this.comments = comments;
            this.keyOrder = keyOrder;
        }
    }
}
