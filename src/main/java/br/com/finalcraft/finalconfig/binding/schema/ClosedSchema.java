package br.com.finalcraft.finalconfig.binding.schema;

import com.fasterxml.jackson.databind.JavaType;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** A fixed-property schema for a closed POJO: a child key it does not declare is obsolete. */
final class ClosedSchema implements Schema {

    private final SchemaCache cache;
    private final Map<String, JavaType> declared;

    ClosedSchema(final SchemaCache cache, final Map<String, JavaType> declared) {
        this.cache = cache;
        this.declared = declared;
    }

    @Override
    public boolean isClosed() {
        return true;
    }

    @Override
    public Set<String> declaredKeys() {
        return Collections.unmodifiableSet(declared.keySet());
    }

    @Override
    public Schema child(final String key) {
        final JavaType type = declared.get(key);
        return type == null ? Schema.OPEN : cache.of(type);
    }

    @Override
    public boolean isObsolete(final String key) {
        return !declared.containsKey(key);
    }
}
