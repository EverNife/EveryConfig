package br.com.finalcraft.everyconfig.binding.schema;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
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
        // A @JsonTypeInfo type discriminator is a sibling key the mapper writes but that is not a bean
        // property, so it would otherwise look obsolete and be pruned under REMOVE — stripping the tag a
        // polymorphic node needs to deserialize. Declare it (open child) so the merge keeps it.
        final String discriminator = discriminatorKey(type.getRawClass());
        if (discriminator != null && !declared.containsKey(discriminator)) {
            declared.put(discriminator, null);
        }
        return new ClosedSchema(this, declared);
    }

    /**
     * The {@link JsonTypeInfo} discriminator property name for a type that writes it as a sibling key
     * ({@code As.PROPERTY} / {@code As.EXISTING_PROPERTY}), or null. Walks up the hierarchy because the
     * annotation usually lives on an abstract base. An empty {@code property()} falls back to the id
     * strategy's default name (e.g. {@code @type}).
     */
    private static String discriminatorKey(final Class<?> raw) {
        for (Class<?> c = raw; c != null && c != Object.class; c = c.getSuperclass()) {
            final JsonTypeInfo info = c.getAnnotation(JsonTypeInfo.class);
            if (info == null) {
                continue;
            }
            if (info.include() != JsonTypeInfo.As.PROPERTY
                    && info.include() != JsonTypeInfo.As.EXISTING_PROPERTY) {
                return null; // wrapper / external forms do not add a sibling key at this node
            }
            return info.property().isEmpty() ? info.use().getDefaultPropertyName() : info.property();
        }
        return null;
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
