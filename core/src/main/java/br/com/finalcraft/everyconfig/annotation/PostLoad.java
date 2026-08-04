package br.com.finalcraft.everyconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to run right AFTER an entity is read (bound) from the tree. The method takes either no
 * parameters or a single {@link br.com.finalcraft.everyconfig.binding.ConfigContext} parameter (whose
 * {@link br.com.finalcraft.everyconfig.binding.ConfigContext#issues() issues()} exposes load problems to
 * inspect or reject). Useful for validation and derived-field computation. Overridden methods run once
 * (de-duped by name).
 *
 * <p>The method-level counterpart of {@link br.com.finalcraft.everyconfig.binding.ConfigLifecycle#postLoad}.
 * Composes in nested position (a field, {@code Map} value, or collection element), with a section at the
 * value's sub-path — see {@link br.com.finalcraft.everyconfig.binding.ConfigLifecycle} for the rules.
 *
 * @see br.com.finalcraft.everyconfig.binding.ConfigLifecycle
 * @see br.com.finalcraft.everyconfig.binding.ConfigContext
 * @see PreLoad
 * @see PreSave
 * @see PostSave
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PostLoad {
}
