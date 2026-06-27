package br.com.finalcraft.everyconfig.binding.merge;
import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.binding.schema.BindingNames;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Converts between a collection of {@code @Id}-bearing entities and the key-major layout the old library
 * used: a map whose section names are the stringified id values and whose bodies omit the id (it would
 * just duplicate the section key). On read the section key is the SOLE authority for the id; a stray,
 * disagreeing id in the body is overridden and reported. This makes {@code read(write(coll))} reproduce
 * the id set exactly.
 */
public final class IdIndexer {

    private IdIndexer() {
    }

    public static ObjectNode toIndexed(final Collection<?> collection, final ObjectMapper mapper) {
        final ObjectNode out = mapper.getNodeFactory().objectNode();
        for (final Object entity : collection) {
            final Field id = BindingNames.requireSingleId(entity.getClass());
            final Object idValue = read(id, entity);
            if (idValue == null || String.valueOf(idValue).isEmpty()) {
                throw new BindException("@Id of " + entity.getClass().getSimpleName() + " is null or empty");
            }
            final JsonNode body = mapper.valueToTree(entity);
            if (body instanceof ObjectNode) {
                // Strip the id under the SAME key the mapper emitted it as (the section key already carries it).
                ((ObjectNode) body).remove(resolvedIdKey(entity.getClass(), id, mapper));
            }
            out.set(String.valueOf(idValue), body);
        }
        return out;
    }

    public static <T> List<T> fromIndexed(final JsonNode node, final Class<T> type, final ObjectMapper mapper,
                                          final List<LoadIssue> issues) {
        final List<T> out = new ArrayList<>();
        if (!(node instanceof ObjectNode)) {
            return out;
        }
        final Field id = BindingNames.requireSingleId(type);
        final Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            final Map.Entry<String, JsonNode> e = it.next();
            final String sectionKey = e.getKey();
            final T entity = mapper.convertValue(e.getValue(), type);
            final Object bodyId = read(id, entity);
            if (bodyId != null && !String.valueOf(bodyId).equals(sectionKey)) {
                issues.add(new LoadIssue(sectionKey, bodyId, id.getType(),
                        "id in the entity body disagrees with the section key; the section key wins"));
            }
            write(id, entity, castKey(sectionKey, id.getType()));
            out.add(entity);
        }
        return out;
    }

    /** The key the mapper actually emits the {@code @Id} field under, so the body strip matches it exactly. */
    private static String resolvedIdKey(final Class<?> type, final Field idField,
                                        final ObjectMapper mapper) {
        final BeanDescription desc = mapper.getSerializationConfig().introspect(mapper.constructType(type));
        for (final BeanPropertyDefinition p : desc.findProperties()) {
            if (p.getField() != null && idField.equals(p.getField().getAnnotated())) {
                return p.getName();
            }
        }
        return BindingNames.keyFor(idField);
    }

    private static Object castKey(final String key, final Class<?> idType) {
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
        throw new BindException("cannot cast section key '" + key + "' to @Id type " + idType.getName());
    }

    private static Object read(final Field f, final Object target) {
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (final IllegalAccessException e) {
            throw new BindException("cannot read @Id field " + f.getName(), e);
        }
    }

    private static void write(final Field f, final Object target, final Object value) {
        try {
            f.setAccessible(true);
            f.set(target, value);
        } catch (final IllegalAccessException e) {
            throw new BindException("cannot set @Id field " + f.getName(), e);
        }
    }
}
