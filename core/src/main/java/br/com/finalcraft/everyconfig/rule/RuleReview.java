package br.com.finalcraft.everyconfig.rule;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as this entity's rule review: it runs after the engines evaluated the entity's own sites
 * and before the policy acts, seeing every violation raised on those sites, deciding each one's outcome and
 * raising violations of the entity's own logic — including logic that reaches outside this config.
 *
 * <p>The method takes a single {@link RuleReviewContext} and returns void. Review decisions outrank both the
 * engine and the policy. Methods run before the {@link RuleReviewer} callback, subclass first, and an
 * overridden method runs once — the same arrangement as the lifecycle hooks.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RuleReview {
}
