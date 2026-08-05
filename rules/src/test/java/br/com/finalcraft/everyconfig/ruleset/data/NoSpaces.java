package br.com.finalcraft.everyconfig.ruleset.data;

import br.com.finalcraft.everyconfig.rule.ConfigRule;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A vocabulary EveryConfig does not ship, declared the way a consumer would declare one: it has to fire
 *  through the standard engine alongside jakarta's and EveryConfig's own. */
@ConfigRule(NoSpacesHandler.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NoSpaces {
}
