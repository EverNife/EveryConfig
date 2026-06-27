package br.com.finalcraft.finalconfig.binding.schema;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and caches a {@link Schema} per {@link JavaType}. A schema is derived once through the SAME
 * mapper (hence the same key naming) the emitter uses, so its declared keys match the keys actually
 * written. Child schemas resolve lazily, so a self-referential type does not recurse forever at build
 * time.
 */
public final class SchemaCache {

    private final ObjectMapper mapper;
    private final Map<JavaType, Schema> cache = new ConcurrentHashMap<>();

    public SchemaCache(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Schema of(final JavaType type) {
        Schema schema = cache.get(type);
        if (schema == null) {
            schema = build(type);
            cache.put(type, schema);
        }
        return schema;
    }

    private Schema build(final JavaType type) {
        if (isOpen(type)) {
            return Schema.OPEN;
        }
        final BeanDescription desc = mapper.getSerializationConfig().introspect(type);
        final List<BeanPropertyDefinition> props = desc.findProperties();
        final Map<String, JavaType> declared = new LinkedHashMap<>();
        for (final BeanPropertyDefinition p : props) {
            final JavaType pt = p.getPrimaryType();
            declared.put(p.getName(), pt);
        }
        return new ClosedSchema(this, declared);
    }

    /** A type with no fixed property set: containers, enums, scalars, and anything in the JDK. */
    private static boolean isOpen(final JavaType type) {
        if (type == null) {
            return true;
        }
        if (type.isMapLikeType() || type.isCollectionLikeType() || type.isArrayType()
                || type.isEnumType() || type.isPrimitive()) {
            return true;
        }
        final Class<?> raw = type.getRawClass();
        if (raw == Object.class) {
            return true;
        }
        final String name = raw.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.");
    }
}
