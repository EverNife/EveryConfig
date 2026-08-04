package br.com.finalcraft.everyconfig.selfdescribe;

import br.com.finalcraft.everyconfig.annotation.EveryConfigCompactCreator;
import br.com.finalcraft.everyconfig.annotation.EveryConfigCompactValue;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link CompactElementResolver} that reads a type's own compact-element declaration: an
 * {@link EveryConfigCompactValue} instance method (no-arg, returns the compact string) plus an
 * {@link EveryConfigCompactCreator} static factory or constructor (takes the string). It holds no registration
 * state — only a memoization cache so a type is scanned once — so it is safe to share the singleton
 * {@link #INSTANCE}. This is the annotation counterpart to a consumer's own lambda-backed resolver.
 */
public final class AnnotationCompactElementResolver implements CompactElementResolver {

    public static final AnnotationCompactElementResolver INSTANCE = new AnnotationCompactElementResolver();

    /** Sentinel cached for a type with no compact declaration, so a negative result is scanned only once. */
    private static final CompactElementCodec<?> ABSENT = new CompactElementCodec<Object>() {
        @Override
        public String encode(final Object value) {
            throw new AssertionError();
        }

        @Override
        public Object decode(final String text) {
            throw new AssertionError();
        }
    };

    private final ConcurrentHashMap<Class<?>, CompactElementCodec<?>> cache = new ConcurrentHashMap<>();

    @Override
    @Nullable
    public CompactElementCodec<?> resolve(final Class<?> type) {
        final CompactElementCodec<?> resolved = cache.computeIfAbsent(type, AnnotationCompactElementResolver::scan);
        return resolved == ABSENT ? null : resolved;
    }

    private static CompactElementCodec<?> scan(final Class<?> type) {
        final Method valueMethod = findValueMethod(type);
        if (valueMethod == null) {
            return ABSENT;
        }
        valueMethod.setAccessible(true);

        final Method factory = findCreatorMethod(type);
        final Constructor<?> ctor = (factory == null) ? findCreatorConstructor(type) : null;
        if (factory == null && ctor == null) {
            throw new IllegalStateException(type.getName() + " declares @EveryConfigCompactValue but has no "
                    + "@EveryConfigCompactCreator (a public static factory or a constructor taking a String)");
        }
        if (factory != null) {
            factory.setAccessible(true);
        }
        if (ctor != null) {
            ctor.setAccessible(true);
        }
        return new ReflectiveCompactCodec(type, valueMethod, factory, ctor);
    }

    /** The no-arg, String-returning instance method annotated {@link EveryConfigCompactValue}, or null. */
    private static Method findValueMethod(final Class<?> type) {
        for (final Method m : type.getMethods()) {
            if (m.isAnnotationPresent(EveryConfigCompactValue.class)
                    && !Modifier.isStatic(m.getModifiers())
                    && m.getParameterCount() == 0
                    && CharSequence.class.isAssignableFrom(m.getReturnType())) {
                return m;
            }
        }
        return null;
    }

    /** The static, single-String-parameter factory annotated {@link EveryConfigCompactCreator}, or null. */
    private static Method findCreatorMethod(final Class<?> type) {
        for (final Method m : type.getMethods()) {
            if (m.isAnnotationPresent(EveryConfigCompactCreator.class)
                    && Modifier.isStatic(m.getModifiers())
                    && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(String.class)) {
                return m;
            }
        }
        return null;
    }

    /** The single-String-parameter constructor annotated {@link EveryConfigCompactCreator}, or null. */
    private static Constructor<?> findCreatorConstructor(final Class<?> type) {
        for (final Constructor<?> c : type.getDeclaredConstructors()) {
            if (c.isAnnotationPresent(EveryConfigCompactCreator.class)
                    && c.getParameterCount() == 1
                    && c.getParameterTypes()[0].isAssignableFrom(String.class)) {
                return c;
            }
        }
        return null;
    }

    /** A codec backed by the resolved reflective members; exactly one of {@code factory}/{@code ctor} is set. */
    private static final class ReflectiveCompactCodec implements CompactElementCodec<Object> {
        private final Class<?> type;
        private final Method valueMethod;
        private final Method factory;
        private final Constructor<?> ctor;

        ReflectiveCompactCodec(final Class<?> type, final Method valueMethod, final Method factory,
                               final Constructor<?> ctor) {
            this.type = type;
            this.valueMethod = valueMethod;
            this.factory = factory;
            this.ctor = ctor;
        }

        @Override
        public String encode(final Object value) {
            try {
                return String.valueOf(valueMethod.invoke(value));
            } catch (final IllegalAccessException e) {
                throw new IllegalStateException("cannot call @EveryConfigCompactValue on " + type.getName(), e);
            } catch (final InvocationTargetException e) {
                throw unwrap("@EveryConfigCompactValue on " + type.getName() + " failed", e);
            }
        }

        @Override
        public Object decode(final String text) {
            try {
                return factory != null ? factory.invoke(null, text) : ctor.newInstance(text);
            } catch (final IllegalAccessException | InstantiationException e) {
                throw new IllegalStateException("cannot call @EveryConfigCompactCreator on " + type.getName(), e);
            } catch (final InvocationTargetException e) {
                throw unwrap("@EveryConfigCompactCreator on " + type.getName() + " failed", e);
            }
        }

        /** A {@code RuntimeException} thrown by the factory propagates as-is (so a lenient bind can pin it);
         *  a checked cause becomes an {@code IllegalStateException}. */
        private static RuntimeException unwrap(final String message, final InvocationTargetException e) {
            final Throwable cause = e.getTargetException();
            if (cause instanceof RuntimeException) {
                return (RuntimeException) cause;
            }
            return new IllegalStateException(message + ": " + cause.getMessage(), cause);
        }
    }
}
