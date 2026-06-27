package br.com.finalcraft.everyconfig.codec;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Capability a {@link Codec} implements when it serializes through a Jackson {@link ObjectMapper} it
 * is willing to expose. Callers {@code instanceof}-check this rather than read a boolean flag. Sharing
 * the mapper makes the dynamic-tree form and the bound-entity form agree by construction.
 *
 * <p>The returned mapper is the codec's ISOLATED instance (a {@code copy()} frozen from any
 * user-supplied mapper at construction). Callers MUST NOT mutate it post-construction; doing so would
 * change serialization for every live config of this format. See {@link FCMapperProfiles#isolate}.
 */
public interface ObjectMapperAware {

    /** The mapper this codec serializes with. Never null. Thread-safe after construction. */
    ObjectMapper objectMapper();
}
