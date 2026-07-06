package br.com.finalcraft.everyconfig.selfdescribe;

import org.jetbrains.annotations.Nullable;

/**
 * Resolves the compact element form (if any) of a type — the pluggable seam that gives EveryConfig a type's
 * compact-in-list codec WITHOUT any global registry. Return a {@link CompactElementCodec} for the types you
 * handle and {@code null} for the rest. Attach one to a codec (the jackson codecs take it in their constructor);
 * the dynamic collection path consults it to decide whether a {@code List<T>} writes as a flat string-list.
 *
 * <p>This is how a consumer teaches EveryConfig the compact form of a type it cannot annotate (e.g. a
 * third-party class): the consumer builds its own resolver over its own registry of encode/decode functions.
 * EveryConfig also ships {@link AnnotationCompactElementResolver}, which reads the annotations; the jackson
 * codecs consult it by default and place a consumer-supplied resolver AHEAD of it.
 */
public interface CompactElementResolver {

    /** The compact codec for {@code type}, or {@code null} if it has none (the element binds normally). */
    @Nullable
    CompactElementCodec<?> resolve(Class<?> type);

    /** A resolver that finds no compact form for any type. */
    CompactElementResolver NONE = type -> null;

    /** {@code first} wins; a type it does not handle ({@code null}) falls through to {@code second}. */
    static CompactElementResolver compose(final CompactElementResolver first, final CompactElementResolver second) {
        final CompactElementResolver a = first != null ? first : NONE;
        final CompactElementResolver b = second != null ? second : NONE;
        if (a == NONE) {
            return b;
        }
        if (b == NONE) {
            return a;
        }
        return type -> {
            final CompactElementCodec<?> c = a.resolve(type);
            return c != null ? c : b.resolve(type);
        };
    }
}
