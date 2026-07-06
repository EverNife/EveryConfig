package br.com.finalcraft.everyconfig.binding.introspect;

import br.com.finalcraft.everyconfig.selfdescribe.EveryConfigMap;
import br.com.finalcraft.everyconfig.selfdescribe.EveryConfigString;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.Deserializers;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The read half of the self-describing mechanism: for a {@link EveryConfigString} / {@link EveryConfigMap}
 * type, binds through the type's static factory (found by convention and cached per class), so a
 * self-describing type needs no central registration on either the write or the read side.
 */
final class SelfDescribingDeserializers extends Deserializers.Base {

    private static final ConcurrentHashMap<Class<?>, Method> STRING_FACTORIES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> MAP_FACTORIES = new ConcurrentHashMap<>();

    @Override
    public JsonDeserializer<?> findBeanDeserializer(final JavaType type, final DeserializationConfig config,
                                                    final BeanDescription beanDesc) {
        final Class<?> raw = type.getRawClass();
        if (EveryConfigString.class.isAssignableFrom(raw)) {
            return new StringForm(raw);
        }
        if (EveryConfigMap.class.isAssignableFrom(raw)) {
            return new MapForm(raw);
        }
        return null;
    }

    /** The convention factory for {@code raw}, resolved once and cached; throws if the type omits it. */
    private static Method factory(final ConcurrentHashMap<Class<?>, Method> cache, final Class<?> raw,
                                  final String name, final Class<?> paramType) {
        return cache.computeIfAbsent(raw, c -> resolveFactory(c, name, paramType));
    }

    private static Method resolveFactory(final Class<?> raw, final String name, final Class<?> paramType) {
        for (final Method m : raw.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getName().equals(name)
                    && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(paramType)) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new IllegalStateException(raw.getName() + " is self-describing but is missing its read factory: "
                + "expected 'public static " + raw.getSimpleName() + " " + name + "("
                + paramType.getSimpleName() + ")'");
    }

    private static Object invoke(final Method factory, final Object arg) throws IOException {
        try {
            return factory.invoke(null, arg);
        } catch (final IllegalAccessException e) {
            throw new IOException("cannot call self-describing factory " + factory, e);
        } catch (final InvocationTargetException e) {
            final Throwable cause = e.getTargetException();
            throw new IOException("self-describing factory " + factory + " failed: " + cause.getMessage(), cause);
        }
    }

    private static final class StringForm extends JsonDeserializer<Object> {
        private final Class<?> raw;

        StringForm(final Class<?> raw) {
            this.raw = raw;
        }

        @Override
        public Object deserialize(final JsonParser p, final DeserializationContext ctxt) throws IOException {
            final String s = p.getValueAsString();
            if (s == null) {
                return null;
            }
            return invoke(factory(STRING_FACTORIES, raw, "fromConfigString", String.class), s);
        }
    }

    private static final class MapForm extends JsonDeserializer<Object> {
        private final Class<?> raw;

        MapForm(final Class<?> raw) {
            this.raw = raw;
        }

        @Override
        public Object deserialize(final JsonParser p, final DeserializationContext ctxt) throws IOException {
            final JavaType mapType = ctxt.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class);
            final Map<String, Object> map = ctxt.readValue(p, mapType);
            return invoke(factory(MAP_FACTORIES, raw, "fromConfigMap", Map.class), map);
        }
    }
}
