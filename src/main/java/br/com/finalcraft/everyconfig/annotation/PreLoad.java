package br.com.finalcraft.everyconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to run right BEFORE an entity is read (bound) from the tree — on the instance the tree
 * will be applied to, so it can set up derived defaults or preconditions before the file values overlay
 * them. Takes no parameters or a single {@code ConfigContext} parameter. Overridden methods run once.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PreLoad {
}
