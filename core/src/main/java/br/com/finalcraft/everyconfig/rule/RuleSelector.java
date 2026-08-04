package br.com.finalcraft.everyconfig.rule;

import java.lang.annotation.Annotation;

/**
 * Claims the annotations an engine — or an introspection caller — understands. It is the seam that lets a
 * vocabulary EveryConfig does not own (a validation API, a consumer's own annotations) be honored without
 * the library knowing it: the selector answers for the annotations its engine can handle, and the bind
 * shortlists a type's sites through it.
 *
 * <p>Stateless and thread-safe: one selector is shared by every bind that reaches it.
 */
public interface RuleSelector {

    boolean claims(Annotation annotation);

    /** Claims annotations whose type is marked {@link ConfigRule} — the built-in vocabulary. */
    RuleSelector CONFIG_RULE_MARKED = annotation ->
            annotation != null && annotation.annotationType().isAnnotationPresent(ConfigRule.class);

    /** Claims whatever either side claims. */
    static RuleSelector union(final RuleSelector first, final RuleSelector second) {
        return annotation -> first.claims(annotation) || second.claims(annotation);
    }
}
