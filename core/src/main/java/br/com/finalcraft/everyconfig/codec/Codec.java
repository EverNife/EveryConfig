package br.com.finalcraft.everyconfig.codec;

import br.com.finalcraft.everyconfig.selfdescribe.CompactElementResolver;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Pluggable serialization strategy for a single on-disk config format.
 *
 * <p>A {@code Codec} is the ONLY component aware of a concrete format (JSON, YAML, ...). Above it, the
 * dynamic path API, comment reconciliation, and entity binding are all format-agnostic.
 *
 * <p>The tree (a raw Jackson {@link JsonNode}) is canonical. {@link #readTree} is the primary surface;
 * {@link #treeToValue}/{@link #valueToTree} are a derived binding view backed by the SAME Jackson
 * mapper, so a dynamic round-trip and a binding round-trip observe identical node shapes.
 *
 * <p>The codec works in {@code String} text; charset/byte handling is the back-store's job, which decodes
 * bytes to a {@code String} with {@link #charset()} before {@link #readTree} and encodes the emitter's
 * {@code String} back to bytes for the atomic write.
 *
 * <p>One {@code Codec} instance is shared by many live configs: it holds a single, thread-safe Jackson
 * {@link com.fasterxml.jackson.databind.ObjectMapper} (configured once at construction, and
 * {@code copy()}-isolated from any user-supplied mapper — see {@link ECMapperProfiles#isolate}). There
 * is no per-file engine, no pool, no ThreadLocal.
 */
public interface Codec {

    // ---- Identity -------------------------------------------------------

    /** Stable short id, e.g. {@code "json"}, {@code "yaml"}. */
    String formatId();

    /**
     * File extensions (without the leading dot, lowercase) this codec claims, e.g.
     * {@code ["yml", "yaml"]}. Used by {@link CodecRegistry} for selection. The first element is the
     * canonical extension used when creating a new file.
     */
    String[] fileExtensions();

    /** This format's comment round-trip fidelity. Never null. */
    CommentFidelity commentFidelity();

    /** The charset the back-store uses to read/write text for this codec. Defaults to UTF-8. */
    default Charset charset() {
        return StandardCharsets.UTF_8;
    }

    // ---- text -> tree (the canonical surface) ---------------------------

    /**
     * Parses raw file text into the canonical tree. Unknown keys are NOT an error — the codec's mapper
     * has {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled and parses the whole document, so every key in
     * the file lands in the tree.
     *
     * <p>Comment text and key order are NOT extracted here. A {@link CommentAware} codec extracts the
     * comment overlay and key order via {@link CommentAware#readComments} in the same load pass.
     *
     * @throws CodecException on malformed input.
     */
    JsonNode readTree(String text);

    /**
     * Serializes a tree to text WITHOUT structural-comment handling. Used by {@code NONE}-fidelity
     * codecs (strict JSON) and as the fallback for the "simple save" path. For {@code LOSSLESS}/
     * {@code LOSSY} codecs the comment emitter ({@link CommentAware}) is used instead.
     *
     * @throws CodecException on a serialization failure.
     */
    String writeTreePlain(JsonNode tree);

    // ---- entity <-> tree (derived binding view) -------------------------

    /**
     * Binds a subtree to a typed value using this codec's mapper. Unknown keys are ignored (they
     * remain in the tree).
     *
     * @throws CodecException on a binding failure.
     */
    <V> V treeToValue(JsonNode node, JavaType type);

    /**
     * Projects a typed value into a tree fragment using this codec's mapper. The binding save MERGES
     * this fragment into the canonical tree; it never replaces the tree.
     *
     * @throws CodecException on a serialization failure.
     */
    JsonNode valueToTree(Object value);

    // ---- shared Jackson engine -----------------------------------------

    /**
     * The Jackson {@link ObjectMapper} this codec serializes with. EveryConfig is Jackson-backed by design,
     * so EVERY codec exposes one: sharing it makes the dynamic-tree form and the bound-entity form agree by
     * construction (a dynamic round-trip and a binding round-trip observe identical node shapes).
     *
     * <p>The returned mapper is the codec's ISOLATED instance (a {@code copy()} frozen from any
     * user-supplied mapper at construction — see {@link ECMapperProfiles#isolate}). It is thread-safe after
     * construction; callers MUST NOT mutate it, since that would change serialization for every live config
     * of this format.
     *
     * @return the codec's mapper; never null.
     */
    ObjectMapper getObjectMapper();

    // ---- compact element form (pluggable, no global registry) -----------

    /**
     * Resolves the COMPACT element form of a type — the codec used when a value is an element of a list on the
     * dynamic path ({@code setValue}/{@code getList}), leaving its rich solo/field form untouched. The default
     * is {@link CompactElementResolver#NONE} (no type has one). A codec attaches a real resolver — the jackson
     * codecs read the {@code @EveryConfigCompactValue}/{@code @EveryConfigCompactCreator} annotations by default
     * and compose a consumer-supplied resolver ahead of them. There is no global registry.
     */
    default CompactElementResolver compactElementResolver() {
        return CompactElementResolver.NONE;
    }
}
