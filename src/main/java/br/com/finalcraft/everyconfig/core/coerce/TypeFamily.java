package br.com.finalcraft.everyconfig.core.coerce;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.Map;

/**
 * The single place that classifies a Java value or type into the families the library treats specially.
 * These are three DISTINCT questions that used to be answered ad hoc in {@code Config} and {@code EntityBinder};
 * co-locating them here keeps the definitions from quietly drifting apart as the code evolves.
 *
 * <p>Turning a value INTO a tree node is a separate concern: that exhaustive per-type dispatch lives in
 * {@link NodeCoercion#toNode}, not here, because it must produce a node for each type rather than answer a
 * yes/no question.
 */
public final class TypeFamily {

    private TypeFamily() {
    }

    /** A native scalar value the dynamic API reads back by scalar coercion rather than entity binding: a
     *  number, char sequence, boolean, character or enum. */
    public static boolean isNativeScalar(final Object value) {
        return value instanceof Number || value instanceof CharSequence || value instanceof Boolean
                || value instanceof Character || value instanceof Enum;
    }

    /** A value that already IS a tree node or a {@code Map}: it serializes to an object but must be stored
     *  raw (there is no schema to merge it against), unlike a genuine POJO entity. */
    public static boolean isPreformedNodeOrMap(final Object value) {
        return value instanceof JsonNode || value instanceof Map;
    }

    /** A user POJO type worth walking for nested binding annotations — not a primitive, array, enum,
     *  {@code Map}, {@code Collection}, or a JDK type ({@code java.*}/{@code javax.*}/{@code jdk.*}). */
    public static boolean isUserPojoType(final Class<?> c) {
        if (c == null || c.isPrimitive() || c.isArray() || c.isEnum()) {
            return false;
        }
        if (Map.class.isAssignableFrom(c) || Collection.class.isAssignableFrom(c)) {
            return false;
        }
        final String n = c.getName();
        return !(n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk."));
    }
}
