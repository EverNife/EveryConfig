package br.com.finalcraft.everyconfig.binding.merge;

import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether an {@link ObjectMapper} writes a type as an object of its own fields, or as a single value of some
 * other shape. A Jackson module answers this by construction: registering a serializer for a type replaces
 * the bean serializer the mapper would otherwise build, and from then on the value occupies ONE node with no
 * fields of its own — nothing inside it can be addressed by a config path.
 *
 * <p>Answers are cached per (mapper, class), because resolving a serializer is a one-time introspection in
 * Jackson too and a codec's mapper is built once and shared for the life of the process — the same lifetime
 * the binder's schema caches already assume.
 */
public final class SerializedShape {

    private static final ConcurrentHashMap<ObjectMapper, ConcurrentHashMap<Class<?>, Boolean>> SHAPES =
            new ConcurrentHashMap<>();

    private SerializedShape() {
    }

    /**
     * Whether {@code mapper} serializes {@code type} as an object of fields (Jackson's bean serializer).
     * {@code false} means the mapper resolved some other serializer for it — or could not resolve one at
     * all, which equally means no field sub-tree would be written. A {@code null} mapper measures nothing
     * and answers {@code true}, so a caller with no mapper keeps whatever behaviour it has without one.
     */
    public static boolean emitsAsBean(final ObjectMapper mapper, final Class<?> type) {
        if (mapper == null || type == null) {
            return true;
        }
        final ConcurrentHashMap<Class<?>, Boolean> byType =
                SHAPES.computeIfAbsent(mapper, m -> new ConcurrentHashMap<Class<?>, Boolean>());
        final Boolean cached = byType.get(type);
        if (cached != null) {
            return cached;
        }
        final boolean bean = resolvesToBeanSerializer(mapper, type);
        byType.put(type, bean);
        return bean;
    }

    private static boolean resolvesToBeanSerializer(final ObjectMapper mapper, final Class<?> type) {
        try {
            final JsonSerializer<?> serializer =
                    mapper.getSerializerProviderInstance().findValueSerializer(type);
            return serializer instanceof BeanSerializerBase;
        } catch (final Exception unresolvable) {
            return false; // a type the mapper cannot build a serializer for writes no field sub-tree either
        }
    }
}
