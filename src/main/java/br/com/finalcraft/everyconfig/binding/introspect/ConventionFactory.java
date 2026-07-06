package br.com.finalcraft.everyconfig.binding.introspect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves and caches a self-describing type's read factory — a {@code public static} method matched by
 * name and single-parameter shape. This is the read half a Java interface cannot mandate (a static method
 * cannot be declared on an interface for implementors), so it is located by convention. Shared by the
 * marker-interface deserializers and the dynamic element-string list API.
 */
public final class ConventionFactory {

    private ConventionFactory() {
    }

    private static final ConcurrentHashMap<Key, Method> CACHE = new ConcurrentHashMap<>();

    /** The {@code public static <raw> name(paramType)} factory for {@code raw}; throws if the type omits it. */
    public static Method require(final Class<?> raw, final String name, final Class<?> paramType) {
        return CACHE.computeIfAbsent(new Key(raw, name, paramType), ConventionFactory::resolve);
    }

    /** Invoke a resolved static factory, unwrapping the reflective wrapper: a {@code RuntimeException} thrown
     *  by the factory itself propagates as-is (so a lenient bind can pin it), other failures become one. */
    public static Object invoke(final Method factory, final Object arg) {
        try {
            return factory.invoke(null, arg);
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException("cannot call factory " + factory, e);
        } catch (final InvocationTargetException e) {
            final Throwable cause = e.getTargetException();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("factory " + factory + " failed: " + cause.getMessage(), cause);
        }
    }

    private static Method resolve(final Key key) {
        for (final Method m : key.raw.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getName().equals(key.name)
                    && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(key.paramType)) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new IllegalStateException(key.raw.getName() + " is self-describing but is missing its read "
                + "factory: expected 'public static " + key.raw.getSimpleName() + " " + key.name + "("
                + key.paramType.getSimpleName() + ")'");
    }

    /** Cache key: a factory is identified by its owning type, method name and parameter type. */
    private static final class Key {
        final Class<?> raw;
        final String name;
        final Class<?> paramType;

        Key(final Class<?> raw, final String name, final Class<?> paramType) {
            this.raw = raw;
            this.name = name;
            this.paramType = paramType;
        }

        @Override
        public boolean equals(final Object o) {
            if (!(o instanceof Key)) {
                return false;
            }
            final Key k = (Key) o;
            return raw.equals(k.raw) && name.equals(k.name) && paramType.equals(k.paramType);
        }

        @Override
        public int hashCode() {
            return (raw.hashCode() * 31 + name.hashCode()) * 31 + paramType.hashCode();
        }
    }
}
