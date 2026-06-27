package br.com.finalcraft.everyconfig.binding.schema;

import com.fasterxml.jackson.databind.JavaType;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** A fixed-property schema for a closed POJO: a child key it does not declare is obsolete. */
final class ClosedSchema implements Schema {

    private final SchemaCache cache;
    private final Map<String, JavaType> declared;
    /** Section-spine segments whose child schema is supplied directly (no JavaType to resolve). */
    private final Map<String, Schema> directChildren;

    ClosedSchema(final SchemaCache cache, final Map<String, JavaType> declared) {
        this(cache, declared, Collections.<String, Schema>emptyMap());
    }

    ClosedSchema(final SchemaCache cache, final Map<String, JavaType> declared,
                 final Map<String, Schema> directChildren) {
        this.cache = cache;
        this.declared = declared;
        this.directChildren = directChildren;
    }

    @Override
    public boolean isClosed() {
        return true;
    }

    @Override
    public Set<String> declaredKeys() {
        final Set<String> keys = new LinkedHashSet<>(declared.keySet());
        keys.addAll(directChildren.keySet());
        return Collections.unmodifiableSet(keys);
    }

    @Override
    public Schema child(final String key) {
        final Schema direct = directChildren.get(key);
        if (direct != null) {
            return direct;
        }
        final JavaType type = declared.get(key);
        return type == null ? Schema.OPEN : cache.of(type);
    }

    @Override
    public boolean isObsolete(final String key) {
        return !declared.containsKey(key) && !directChildren.containsKey(key);
    }
}
