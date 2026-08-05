package br.com.finalcraft.everyconfig.ruleset;

import br.com.finalcraft.everyconfig.rule.ConfigRule;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The collection or array must hold no repeated entry — the list a config file grows by hand, where the same
 * world or permission ends up written twice and the duplicate silently does nothing.
 *
 * <p>Equality is the elements' own {@code equals()}, and two nulls count as a repeat. A null field passes:
 * absence is {@code @NotNull}'s business.
 */
@Documented
@ConfigRule(UniqueHandler.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Unique {
}
