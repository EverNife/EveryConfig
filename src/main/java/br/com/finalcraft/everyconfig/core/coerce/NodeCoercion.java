package br.com.finalcraft.everyconfig.core.coerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The single place values cross between Java and Jackson: {@code Object} → {@code JsonNode} (inbound)
 * and {@code JsonNode} → Java value (outbound). All typed getters route through here, so "what does
 * getInt do to a string node" is defined in exactly one place. Numeric getters tolerate a number stored
 * as a string (e.g. a value once written as a quoted long still reads back), applied uniformly to int,
 * long and double so the behavior is symmetric.
 */
public final class NodeCoercion {

    private final JsonNodeFactory nf;

    /** Arbitrary-POJO → node escape, injected by the codec layer (ObjectMapperAware). */
    private Function<Object, JsonNode> pojoToNode = o -> {
        throw new UnsupportedOperationException(
                "no ObjectMapper bound; cannot serialize " + o.getClass().getName());
    };

    public NodeCoercion(final JsonNodeFactory nf) {
        this.nf = nf;
    }

    public void setPojoToNode(final Function<Object, JsonNode> pojoToNode) {
        this.pojoToNode = pojoToNode;
    }

    // ==================== INBOUND: Object -> JsonNode ====================

    /** Returns null ONLY for Java {@code null} (the delete signal); collection elements use NullNode. */
    public JsonNode toNode(final Object value) {
        if (value == null) {
            return null;
        }
        return toNodeNonNull(value);
    }

    private JsonNode toNodeNonNull(final Object value) {
        if (value instanceof JsonNode) {
            return (JsonNode) value;
        }
        if (value instanceof String) {
            return nf.textNode((String) value);
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return nf.numberNode(((Number) value).intValue());
        }
        if (value instanceof Long) {
            return nf.numberNode((Long) value);
        }
        if (value instanceof Double) {
            return nf.numberNode((Double) value);
        }
        if (value instanceof Float) {
            return nf.numberNode((Float) value);
        }
        if (value instanceof BigInteger) {
            return nf.numberNode((BigInteger) value);
        }
        if (value instanceof BigDecimal) {
            return nf.numberNode((BigDecimal) value);
        }
        if (value instanceof Boolean) {
            return nf.booleanNode((Boolean) value);
        }
        if (value instanceof Enum) {
            return nf.textNode(((Enum<?>) value).name());
        }
        if (value instanceof Map) {
            final ObjectNode on = nf.objectNode();
            for (final Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                on.set(String.valueOf(e.getKey()), elementNode(e.getValue()));
            }
            return on;
        }
        if (value instanceof Iterable) {
            final ArrayNode an = nf.arrayNode();
            for (final Object e : (Iterable<?>) value) {
                an.add(elementNode(e));
            }
            return an;
        }
        if (value instanceof Object[]) {
            final ArrayNode an = nf.arrayNode();
            for (final Object e : (Object[]) value) {
                an.add(elementNode(e));
            }
            return an;
        }
        return pojoToNode.apply(value);
    }

    /** Inside a collection, a Java null is an explicit null element (NullNode), not a delete. */
    private JsonNode elementNode(final Object value) {
        return value == null ? nf.nullNode() : toNodeNonNull(value);
    }

    // ==================== OUTBOUND: JsonNode -> Java value ====================

    public String asString(final JsonNode n) {
        if (isAbsentish(n)) {
            return null;
        }
        if (n.isTextual()) {
            return n.textValue();
        }
        if (n.isArray()) {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n.size(); i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append(n.get(i).asText());
            }
            return sb.toString();
        }
        if (n.isObject()) {
            // An object is not a string in any useful sense; fall back to the caller's default
            // (mirrors the numeric getters, where a type mismatch yields null rather than a fabricated value).
            return null;
        }
        return n.asText(); // numeric / boolean canonical
    }

    public Integer asInt(final JsonNode n) {
        if (isAbsentish(n)) {
            return null;
        }
        if (n.isNumber()) {
            return n.asInt();
        }
        if (n.isTextual()) {
            final String s = n.textValue().trim();
            if (s.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(s).intValueExact();
            } catch (final Exception e) {
                return null;
            }
        }
        return null;
    }

    /** Tolerant of a long stored as a quoted string (e.g. {@code "1700000000000"} or {@code "1.0"}). */
    public Long asLong(final JsonNode n) {
        if (isAbsentish(n)) {
            return null;
        }
        if (n.isNumber()) {
            return n.asLong();
        }
        if (n.isTextual()) {
            final String s = n.textValue().trim();
            if (s.isEmpty()) {
                return null;
            }
            try {
                return Long.parseLong(s);
            } catch (final NumberFormatException e) {
                try {
                    return new BigDecimal(s).longValueExact();
                } catch (final Exception e2) {
                    return null;
                }
            }
        }
        return null;
    }

    public Double asDouble(final JsonNode n) {
        if (isAbsentish(n)) {
            return null;
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        if (n.isTextual()) {
            final String s = n.textValue().trim();
            if (s.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(s);
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public Boolean asBoolean(final JsonNode n) {
        if (isAbsentish(n)) {
            return null;
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isTextual()) {
            final String s = n.textValue().trim();
            if (s.equalsIgnoreCase("true")) {
                return Boolean.TRUE;
            }
            if (s.equalsIgnoreCase("false")) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /** ArrayNode → list of element texts; non-array → null (caller applies its default). */
    public List<String> asStringList(final JsonNode n) {
        if (n == null || !n.isArray()) {
            return null;
        }
        final List<String> out = new ArrayList<>(n.size());
        for (final JsonNode e : n) {
            out.add(e.asText());
        }
        return out;
    }

    /** ArrayNode → list of unwrapped scalars (containers stay as JsonNode); non-array → null. */
    public List<Object> asList(final JsonNode n) {
        if (n == null || !n.isArray()) {
            return null;
        }
        final List<Object> out = new ArrayList<>(n.size());
        for (final JsonNode e : n) {
            out.add(unwrap(e));
        }
        return out;
    }

    /** Unwrap a single node to a plain Java scalar; non-scalars are returned as the node. */
    public Object unwrap(final JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isTextual()) {
            return n.textValue();
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isInt()) {
            return n.intValue();
        }
        if (n.isLong()) {
            return n.longValue();
        }
        if (n.isFloatingPointNumber()) {
            return n.doubleValue();
        }
        if (n.isBigInteger()) {
            return n.bigIntegerValue();
        }
        if (n.isNumber()) {
            return n.numberValue();
        }
        return n; // object / array kept as node
    }

    private static boolean isAbsentish(final JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode();
    }
}
