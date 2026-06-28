package br.com.finalcraft.everyconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to run right AFTER an entity is read (bound) from the tree. The method takes either no
 * parameters or a single {@code ConfigContext} parameter (whose {@code issues()} exposes load problems
 * to inspect or reject). Useful
 * for validation and derived-field computation. Overridden methods run once (de-duped by name).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PostLoad {
}
