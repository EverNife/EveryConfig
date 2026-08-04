package br.com.finalcraft.everyconfig.rule;

import br.com.finalcraft.everyconfig.binding.BindException;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The built-in engine: it reads {@code @ConfigRule(SomeHandler.class)} off the rule annotation and delegates
 * to that handler, keeping one instance per handler class. Attached to every config by default, which is
 * what makes a self-contained rule annotation work with no setup line at all.
 *
 * <p>It holds no registration state — only the handler memoization — so the shared {@link #INSTANCE} is the
 * way to use it. A rule left on {@link RuleHandler.None} (the marker default) is a declared fact and
 * nothing more: this engine skips it, and whichever attached engine claims the annotation handles it.
 */
public final class AnnotationRuleEngine implements RuleEngine {

    public static final AnnotationRuleEngine INSTANCE = new AnnotationRuleEngine();

    /** One handler per handler class. A class that cannot be instantiated re-throws on every call instead of
     *  caching a failure, so the message keeps pointing at the defect. */
    private static final ConcurrentHashMap<Class<? extends RuleHandler>, RuleHandler> HANDLERS =
            new ConcurrentHashMap<>();

    private AnnotationRuleEngine() {
    }

    @Override
    public void apply(final RuleContext context) {
        final RuleHandler handler = handlerFor(context.site().rule());
        if (handler != null) {
            handler.check(context);
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        final RuleHandler handler = handlerFor(site.rule());
        return handler != null ? handler.describe(site) : Collections.<String>emptyList();
    }

    /** The handler declared by the annotation's {@code @ConfigRule} marker, or null when the rule is inert. */
    private static RuleHandler handlerFor(final Annotation rule) {
        if (rule == null) {
            return null;
        }
        final ConfigRule marker = rule.annotationType().getAnnotation(ConfigRule.class);
        if (marker == null || marker.value() == RuleHandler.None.class) {
            return null;
        }
        return HANDLERS.computeIfAbsent(marker.value(), AnnotationRuleEngine::instantiate);
    }

    private static RuleHandler instantiate(final Class<? extends RuleHandler> type) {
        final Constructor<? extends RuleHandler> constructor;
        try {
            constructor = type.getDeclaredConstructor();
        } catch (final NoSuchMethodException absent) {
            throw new BindException("rule handler " + type.getName() + " has no no-argument constructor. "
                    + "EveryConfig creates one instance per handler class and shares it, so a handler must be "
                    + "constructible with no arguments - give it a public no-argument constructor and keep its "
                    + "configuration in the rule annotation's attributes.");
        }
        try {
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (final Exception failed) {
            throw new BindException("rule handler " + type.getName() + " could not be instantiated: "
                    + failed.getMessage() + ". A handler is created once and shared, so its constructor must "
                    + "be accessible and must not depend on per-bind state.", failed);
        }
    }
}
