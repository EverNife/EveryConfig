package br.com.finalcraft.everyconfig.binding.schema;

import com.fasterxml.jackson.databind.JavaType;

import java.util.Collections;
import java.util.Set;

/**
 * The schema of a {@code @KeyIndex} collection stored key-major: every key is one element, so no key is
 * declared ahead of time, each child is an element of the same type, and the collection's elements are the
 * node's whole membership.
 */
final class KeyedCollectionSchema implements Schema {

    private final SchemaCache cache;
    private final JavaType elementType;

    KeyedCollectionSchema(final SchemaCache cache, final JavaType elementType) {
        this.cache = cache;
        this.elementType = elementType;
    }

    @Override
    public boolean isClosed() {
        return false;
    }

    @Override
    public Set<String> declaredKeys() {
        return Collections.emptySet();
    }

    @Override
    public Schema child(final String key) {
        return cache.of(elementType);
    }

    @Override
    public boolean isObsolete(final String key) {
        return false;
    }

    @Override
    public boolean ownsMembership() {
        return true;
    }
}
