package br.com.finalcraft.everyconfig.binding.merge;

import br.com.finalcraft.everyconfig.annotation.PostLoad;
import br.com.finalcraft.everyconfig.annotation.PostSave;
import br.com.finalcraft.everyconfig.annotation.PreLoad;
import br.com.finalcraft.everyconfig.annotation.PreSave;
import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.binding.ConfigContext;
import br.com.finalcraft.everyconfig.binding.ConfigLifecycle;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.config.section.ConfigSection;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    /** The annotated hook methods of each class, resolved once on first use and reused thereafter — the
     *  method set depends only on the class, so re-walking the hierarchy on every bind is wasted work. */
    private static final ConcurrentHashMap<Class<?>, Map<Phase, List<Method>>> HOOKS = new ConcurrentHashMap<>();

    private LifecycleInvoker() {
    }

    public static void fire(final Object pojo, final Phase phase, final ConfigSection section,
                            final List<LoadIssue> issues) {
        if (pojo == null) {
            return;
        }
        final ConfigContext context = new ConfigContext(section, issues);
        for (final Method m : hooksOf(pojo.getClass()).get(phase)) {
            invokeOne(pojo, m, phase, context);
        }
        if (pojo instanceof ConfigLifecycle) {
            invokeInterface((ConfigLifecycle) pojo, phase, context);
        }
    }

    private static Map<Phase, List<Method>> hooksOf(final Class<?> type) {
        return HOOKS.computeIfAbsent(type, LifecycleInvoker::resolveHooks);
    }

    /**
     * Collect the hook methods per phase for {@code type}: walk the hierarchy subclass-first and, within
     * each phase, keep the first method seen per name so an overridden hook runs once (the most-derived
     * override). Signatures are validated at invoke time, as before, so a bad {@code @PreSave} surfaces
     * only when a save fires — not when this map is built.
     */
    private static Map<Phase, List<Method>> resolveHooks(final Class<?> type) {
        final Map<Phase, List<Method>> byPhase = new EnumMap<>(Phase.class);
        for (final Phase phase : Phase.values()) {
            final List<Method> methods = new ArrayList<>();
            final Set<String> seenNames = new HashSet<>();
            Class<?> c = type;
            while (c != null && c != Object.class) {
                for (final Method m : c.getDeclaredMethods()) {
                    if (m.isAnnotationPresent(phase.annotation) && seenNames.add(m.getName())) {
                        methods.add(m);
                    }
                }
                c = c.getSuperclass();
            }
            byPhase.put(phase, methods);
        }
        return byPhase;
    }

    private static void invokeInterface(final ConfigLifecycle entity, final Phase phase,
                                        final ConfigContext context) {
        switch (phase) {
            case PRE_LOAD:
                entity.preLoad(context);
                break;
            case POST_LOAD:
                entity.postLoad(context);
                break;
            case PRE_SAVE:
                entity.preSave(context);
                break;
            case POST_SAVE:
                entity.postSave(context);
                break;
            default:
                break;
        }
    }

    private static void invokeOne(final Object pojo, final Method m, final Phase phase,
                                  final ConfigContext context) {
        final Class<?>[] params = m.getParameterTypes();
        final String tag = "@" + phase.annotation.getSimpleName() + " '" + m.getName() + "'";
        try {
            m.setAccessible(true);
            if (params.length == 0) {
                m.invoke(pojo);
            } else if (params.length == 1 && params[0].isAssignableFrom(ConfigContext.class)) {
                m.invoke(pojo, context);
            } else {
                throw new BindException(tag + " must take no parameters or a single ConfigContext parameter");
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
