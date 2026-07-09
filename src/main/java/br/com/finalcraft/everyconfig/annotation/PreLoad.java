package br.com.finalcraft.everyconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to run right BEFORE an entity is read (bound) from the tree — on the instance the tree
 * will be applied to, so it can set up derived defaults or preconditions before the file values overlay
 * them. Takes no parameters or a single {@link br.com.finalcraft.everyconfig.binding.ConfigContext}
 * parameter. Overridden methods run once.
 *
 * <p>The method-level counterpart of {@link br.com.finalcraft.everyconfig.binding.ConfigLifecycle#preLoad}.
 *
 * @see br.com.finalcraft.everyconfig.binding.ConfigLifecycle
 * @see br.com.finalcraft.everyconfig.binding.ConfigContext
 * @see PostLoad
 * @see PreSave
 * @see PostSave
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PreLoad {
}
