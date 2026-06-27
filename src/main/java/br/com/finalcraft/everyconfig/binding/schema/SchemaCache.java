package br.com.finalcraft.everyconfig.binding.schema;

import br.com.finalcraft.everyconfig.annotation.Section;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

import java.lang.reflect.Field;
import java.util.Collections;
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

        // A @Section field is relocated to a nested dotted path on save, so its flat key is not where the
        // data ends up. Mirror the relocation in the schema: drop the flat key and declare the section
        // spine (root segment ... leaf) as owned, so the relocated subtree is never pruned as obsolete.
        final Map<String, Object> sectionTree = sectionSpine(type.getRawClass(), declared);
        if (!sectionTree.isEmpty()) {
            return new ClosedSchema(this, declared, buildSpineChildren(sectionTree));
        }
        return new ClosedSchema(this, declared);
    }

    /**
     * Removes each {@code @Section} field's flat key from {@code declared} and returns a nested map of the
     * section paths ({@code segment -> sub-map}, leaf marked by a non-map value), shared across fields so
     * sibling sections under one path merge into one spine.
     */
    private static Map<String, Object> sectionSpine(final Class<?> raw, final Map<String, JavaType> declared) {
        final Map<String, Object> tree = new LinkedHashMap<>();
        for (final Field f : BindingNames.allFields(raw)) {
            final Section s = f.getAnnotation(Section.class);
            if (s == null || s.value().isEmpty()) {
                continue;
            }
            final String flatKey = BindingNames.keyFor(f);
            declared.remove(flatKey);
            insertSpine(tree, (s.value() + "." + flatKey).split("\\."));
        }
        return tree;
    }

    @SuppressWarnings("unchecked")
    private static void insertSpine(final Map<String, Object> tree, final String[] segments) {
        Map<String, Object> cur = tree;
        for (int i = 0; i < segments.length; i++) {
            if (i == segments.length - 1) {
                cur.put(segments[i], Boolean.TRUE); // leaf marker (the relocated field's own value)
            } else {
                Object next = cur.get(segments[i]);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<String, Object>();
                    cur.put(segments[i], next);
                }
                cur = (Map<String, Object>) next;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Schema> buildSpineChildren(final Map<String, Object> tree) {
        final Map<String, Schema> out = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : tree.entrySet()) {
            if (e.getValue() instanceof Map) {
                out.put(e.getKey(), new ClosedSchema(this, Collections.<String, JavaType>emptyMap(),
                        buildSpineChildren((Map<String, Object>) e.getValue())));
            } else {
                // The leaf is the relocated field's own value; leave it OPEN so its contents are not pruned.
                out.put(e.getKey(), Schema.OPEN);
            }
        }
        return out;
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
