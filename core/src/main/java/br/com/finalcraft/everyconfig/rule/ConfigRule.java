package br.com.finalcraft.everyconfig.rule;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an annotation as a config rule: a fact declared about a field, a type or a computed method,
 * addressed to whichever engine understands it. An annotation carrying this marker is claimed by
 * {@link RuleSelector#CONFIG_RULE_MARKED}, so it is visible to introspection and to the built-in
 * {@link AnnotationRuleEngine} with no registration step.
 *
 * <p>A rule annotation targeting {@link ElementType#TYPE} declares an invariant of the entity itself: its
 * site carries the entity's path and no field. Marking is not the only way in — an engine claims
 * annotations through its own {@link RuleSelector}, so a foreign vocabulary that can never carry this
 * marker is honored just the same.
 */
@Documented
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigRule {

    /**
     * The handler {@link AnnotationRuleEngine} delegates to for this rule. The default,
     * {@link RuleHandler.None}, leaves the rule inert until an attached engine claims it — what a
     * declaration meant only to be READ (by a screen builder, a documentation pass) wants.
     */
    Class<? extends RuleHandler> value() default RuleHandler.None.class;
}
