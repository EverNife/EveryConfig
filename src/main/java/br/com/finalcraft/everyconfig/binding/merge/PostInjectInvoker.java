package br.com.finalcraft.everyconfig.binding.merge;
import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.binding.LoadIssue;

import br.com.finalcraft.everyconfig.annotation.PostInject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs an entity's {@code @PostInject} methods after binding. It walks the class hierarchy, de-dups by
 * method name (so an overridden method runs once, not once per level), and accepts a method that takes
 * either no parameters or a single {@code List<LoadIssue>}.
 */
public final class PostInjectInvoker {

    private PostInjectInvoker() {
    }

    public static void invoke(final Object pojo, final List<LoadIssue> issues) {
        if (pojo == null) {
            return;
        }
        final Set<String> invoked = new HashSet<>();
        Class<?> c = pojo.getClass();
        while (c != null && c != Object.class) {
            for (final Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(PostInject.class) && invoked.add(m.getName())) {
                    invokeOne(pojo, m, issues);
                }
            }
            c = c.getSuperclass();
        }
    }

    private static void invokeOne(final Object pojo, final Method m, final List<LoadIssue> issues) {
        final Class<?>[] params = m.getParameterTypes();
        try {
            m.setAccessible(true);
            if (params.length == 0) {
                m.invoke(pojo);
            } else if (params.length == 1 && params[0].isAssignableFrom(List.class)) {
                m.invoke(pojo, issues);
            } else {
                throw new BindException("@PostInject method '" + m.getName()
                        + "' must take no parameters or a single List<LoadIssue> parameter");
            }
        } catch (final InvocationTargetException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof BindException) {
                throw (BindException) cause;
            }
            throw new BindException("@PostInject '" + m.getName() + "' failed: " + cause.getMessage(), cause);
        } catch (final BindException e) {
            throw e;
        } catch (final Exception e) {
            throw new BindException("@PostInject '" + m.getName() + "' could not be invoked", e);
        }
    }
}
