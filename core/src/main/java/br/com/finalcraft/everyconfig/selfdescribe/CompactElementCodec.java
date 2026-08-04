package br.com.finalcraft.everyconfig.selfdescribe;

/**
 * A type's COMPACT string form for use as a collection element: encodes a value to a single line and rebuilds
 * it from that line. It is used ONLY on the dynamic collection path ({@code setValue}/{@code getList}); the
 * type keeps its rich solo/field form (owned by the codec's mapper) untouched, so a {@code List<T>} can be a
 * flat string-list while a solo {@code T} stays a full object.
 *
 * <p>Which types have one is decided by a {@link CompactElementResolver} — from the
 * {@code @EveryConfigCompactValue}/{@code @EveryConfigCompactCreator} annotations on a type you own, or from a
 * resolver a consumer attaches to the codec for a type it cannot annotate.
 *
 * @param <T> the element type
 */
public interface CompactElementCodec<T> {

    /** This value's compact one-line form. */
    String encode(T value);

    /** Rebuild a value from its compact form. May throw to signal a malformed element (read leniently skips it). */
    T decode(String text);
}
