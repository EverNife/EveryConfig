package br.com.finalcraft.everyconfig.binding.merge;
import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.binding.schema.BindingNames;
import br.com.finalcraft.everyconfig.core.tree.DPath;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Converts between a collection of {@code @KeyIndex}-bearing entities and a key-major layout:
 * a map whose section names are the stringified id values and whose bodies omit the id (it would
 * just duplicate the section key). On read the section key is the SOLE authority for the id; a stray,
 * disagreeing id in the body is overridden and reported. This makes {@code read(write(coll))} reproduce
 * the id set exactly.
 */
public final class KeyIndexer {

    private KeyIndexer() {
    }

    /** True when {@code type} declares at least one {@code @KeyIndex} field — the signal that a collection of
     *  it serializes key-major. {@link #toIndexed} then validates there is exactly one. Cached per class. */
    public static boolean isKeyIndexed(final Class<?> type) {
        return BindingNames.isKeyIndexed(type);
    }

    public static ObjectNode toIndexed(final Collection<?> collection, final ObjectMapper mapper) {
        final ObjectNode out = mapper.getNodeFactory().objectNode();
        for (final Object entity : collection) {
            final Field id = BindingNames.requireSingleKeyIndex(entity.getClass());
            final String key = sectionKey(entity, id);
            if (out.has(key)) {
                throw duplicateKey(key, entity);
            }
            final JsonNode body = mapper.valueToTree(entity);
            if (body instanceof ObjectNode) {
                // Strip the id under the SAME key the mapper emitted it as (the section key already carries it).
                ((ObjectNode) body).remove(resolvedIdKey(entity.getClass(), id, mapper.getSerializationConfig()));
            }
            out.set(key, body);
        }
        return out;
    }

    /** The section name {@code entity} is stored under: its id, as text. */
    static String sectionKey(final Object entity, final Field id) {
        final Object idValue = read(id, entity);
        final String key = idValue == null ? null : String.valueOf(idValue);
        if (key == null || key.trim().isEmpty()) { // a blank id makes a useless/confusing section name
            throw new BindException("@KeyIndex of " + entity.getClass().getSimpleName() + " is null or blank");
        }
        return key;
    }

    /** Two elements sharing an id would silently collapse into one section; this is the refusal instead. */
    static BindException duplicateKey(final String key, final Object entity) {
        return new BindException("duplicate @KeyIndex value '" + key + "' in the collection of "
                + entity.getClass().getSimpleName() + "; @KeyIndex values must be unique");
    }

    /** Stamp {@code sectionKey} onto {@code entity}'s {@code @KeyIndex} field, cast to the field's type. */
    static void assignId(final Object entity, final String sectionKey) {
        final Field id = BindingNames.requireSingleKeyIndex(entity.getClass());
        write(id, entity, castKey(sectionKey, id.getType()));
    }

    /**
     * Read the key-major section stored at {@code sectionPath} as a list of {@code type}. Every issue is
     * keyed at the entry's own path — {@code sectionPath.<key>} — so a caller holding issues from several
     * reads can still tell which entry of which collection each one names.
     */
    public static <T> List<T> fromIndexed(final JsonNode node, final String sectionPath, final Class<T> type,
                                          final ObjectMapper mapper, final List<LoadIssue> issues) {
        final List<T> out = new ArrayList<>();
        if (!(node instanceof ObjectNode)) {
            return out;
        }
        final Field id = BindingNames.requireSingleKeyIndex(type);
        final Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            final Map.Entry<String, JsonNode> e = it.next();
            final String sectionKey = e.getKey();
            final String entryPath = DPath.joinSegment(sectionPath, sectionKey);
            // Lenient, like every other read path: a single bad entry (an unbindable body, or a section key
            // that cannot be cast to the id type — e.g. a corrupted UUID) is recorded and skipped, never
            // failing the whole read.
            try {
                final T entity = mapper.convertValue(e.getValue(), type);
                final Object bodyId = read(id, entity);
                if (bodyId != null && !String.valueOf(bodyId).equals(sectionKey)) {
                    issues.add(new LoadIssue(entryPath, bodyId, id.getType(),
                            "id in the entity body disagrees with the section key; the section key wins"));
                }
                write(id, entity, castKey(sectionKey, id.getType()));
                out.add(entity);
            } catch (final RuntimeException badEntry) {
                issues.add(new LoadIssue(entryPath, null, type,
                        "could not read @KeyIndex entry '" + sectionKey + "': " + badEntry.getMessage()));
            }
        }
        return out;
    }

    /** The key the mapper actually emits the {@code @KeyIndex} field under, so the body strip matches it exactly. */
    static String resolvedIdKey(final Class<?> type, final Field idField, final SerializationConfig config) {
        final BeanDescription desc = config.introspect(config.constructType(type));
        for (final BeanPropertyDefinition p : desc.findProperties()) {
            if (p.getField() != null && idField.equals(p.getField().getAnnotated())) {
                return p.getName();
            }
        }
        return BindingNames.keyFor(idField);
    }

    private static Object castKey(final String key, final Class<?> idType) {
        try {
            return parseKey(key, idType);
        } catch (final IllegalArgumentException malformed) { // a NumberFormatException is one
            throw new BindException("The section key '" + key + "' is not a valid " + idType.getSimpleName()
                    + ", the type of the @KeyIndex field it names. Rename the section to a valid "
                    + idType.getSimpleName() + ".", malformed);
        }
    }

    private static Object parseKey(final String key, final Class<?> idType) {
        if (idType == String.class) {
            return key;
        }
        if (idType == UUID.class) {
            return UUID.fromString(key);
        }
        if (idType == Integer.class || idType == int.class) {
            return Integer.valueOf(key);
        }
        if (idType == Long.class || idType == long.class) {
            return Long.valueOf(key);
        }
        if (idType == Double.class || idType == double.class) {
            return Double.valueOf(key);
        }
        if (idType == Float.class || idType == float.class) {
            return Float.valueOf(key);
        }
        if (idType == Short.class || idType == short.class) {
            return Short.valueOf(key);
        }
        if (idType == Byte.class || idType == byte.class) {
            return Byte.valueOf(key);
        }
        if (idType == Boolean.class || idType == boolean.class) {
            return Boolean.valueOf(key);
        }
        throw new BindException("cannot cast section key '" + key + "' to @KeyIndex type " + idType.getName());
    }

    private static Object read(final Field f, final Object target) {
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (final IllegalAccessException e) {
            throw new BindException("cannot read @KeyIndex field " + f.getName(), e);
        }
    }

    private static void write(final Field f, final Object target, final Object value) {
        try {
            f.setAccessible(true);
            f.set(target, value);
        } catch (final IllegalAccessException e) {
            throw new BindException("cannot set @KeyIndex field " + f.getName(), e);
        }
    }
}
