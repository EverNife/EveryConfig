package br.com.finalcraft.everyconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to run right AFTER an entity is written (merged) into the tree. Fires on the
 * POJO-to-tree write, not the file flush ({@code Config.save}). Takes no parameters or a single
 * {@link br.com.finalcraft.everyconfig.binding.ConfigContext} parameter. Overridden methods run once
 * (de-duped by name).
 *
 * <p>The method-level counterpart of {@link br.com.finalcraft.everyconfig.binding.ConfigLifecycle#postSave}.
 * Composes in nested position (a field, {@code Map} value, or collection element), with a section at the
 * value's sub-path — see {@link br.com.finalcraft.everyconfig.binding.ConfigLifecycle} for the rules.
 *
 * @see br.com.finalcraft.everyconfig.binding.ConfigLifecycle
 * @see br.com.finalcraft.everyconfig.binding.ConfigContext
 * @see PreLoad
 * @see PostLoad
 * @see PreSave
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PostSave {
}
