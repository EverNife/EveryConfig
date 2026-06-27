package br.com.finalcraft.everyconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to run right after an entity is bound from the tree. The method takes either no
 * parameters or a single {@code List<LoadIssue>} parameter (to inspect or reject load problems). Useful
 * for validation and derived-field computation.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PostInject {
}
