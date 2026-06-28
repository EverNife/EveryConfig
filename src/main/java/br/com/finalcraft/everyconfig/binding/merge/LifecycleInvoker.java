package br.com.finalcraft.everyconfig.binding.merge;

import br.com.finalcraft.everyconfig.annotation.PostLoad;
import br.com.finalcraft.everyconfig.annotation.PostSave;
import br.com.finalcraft.everyconfig.annotation.PreLoad;
import br.com.finalcraft.everyconfig.annotation.PreSave;
import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.binding.ConfigLifecycle;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.config.section.ConfigSection;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fires an entity's lifecycle hooks for one bind phase. It runs the methods annotated with that phase's
 * annotation (walking the class hierarchy, de-duped by name so an overridden method runs once; each takes
 * no parameters or a single {@code List<LoadIssue>}), then — if the entity implements
 * {@link ConfigLifecycle} — the matching interface callback with the bound {@link ConfigSection}.
 */
public final class LifecycleInvoker {

    /** The four bind lifecycle phases, each tied to its method annotation. */
    public enum Phase {
        PRE_LOAD(PreLoad.class),
        POST_LOAD(PostLoad.class),
        PRE_SAVE(PreSave.class),
        POST_SAVE(PostSave.class);

        private final Class<? extends Annotation> annotation;

        Phase(final Class<? extends Annotation> annotation) {
            this.annotation = annotation;
        }
    }

    private LifecycleInvoker() {
    }

    public static void fire(final Object pojo, final Phase phase, final ConfigSection section,
                            final List<LoadIssue> issues) {
        if (pojo == null) {
            return;
        }
        final Set<String> invoked = new HashSet<>();
        Class<?> c = pojo.getClass();
        while (c != null && c != Object.class) {
            for (final Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(phase.annotation) && invoked.add(m.getName())) {
                    invokeOne(pojo, m, phase, issues);
                }
            }
            c = c.getSuperclass();
        }
        if (pojo instanceof ConfigLifecycle) {
            invokeInterface((ConfigLifecycle) pojo, phase, section);
        }
    }

    private static void invokeInterface(final ConfigLifecycle entity, final Phase phase,
                                        final ConfigSection section) {
        switch (phase) {
            case PRE_LOAD:
                entity.preLoad(section);
                break;
            case POST_LOAD:
                entity.postLoad(section);
                break;
            case PRE_SAVE:
                entity.preSave(section);
                break;
            case POST_SAVE:
                entity.postSave(section);
                break;
            default:
                break;
        }
    }

    private static void invokeOne(final Object pojo, final Method m, final Phase phase,
                                  final List<LoadIssue> issues) {
        final Class<?>[] params = m.getParameterTypes();
        final String tag = "@" + phase.annotation.getSimpleName() + " '" + m.getName() + "'";
        try {
            m.setAccessible(true);
            if (params.length == 0) {
                m.invoke(pojo);
            } else if (params.length == 1 && params[0].isAssignableFrom(List.class)) {
                m.invoke(pojo, issues);
            } else {
                throw new BindException(tag + " must take no parameters or a single List<LoadIssue> parameter");
            }
        } catch (final InvocationTargetException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof BindException) {
                throw (BindException) cause;
            }
            throw new BindException(tag + " failed: " + cause.getMessage(), cause);
        } catch (final BindException e) {
            throw e;
        } catch (final Exception e) {
            throw new BindException(tag + " could not be invoked", e);
        }
    }
}
