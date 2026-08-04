package br.com.finalcraft.everyconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to run right BEFORE an entity is written (merged) into the tree, so it can compute or
 * scrub fields before they are serialized. Note this fires on the POJO-to-tree write, which precedes the
 * file flush ({@code Config.save}), not the flush itself. Takes no parameters or a single
 * {@link br.com.finalcraft.everyconfig.binding.ConfigContext} parameter. Overridden methods run once.
 *
 * <p>The method-level counterpart of {@link br.com.finalcraft.everyconfig.binding.ConfigLifecycle#preSave}.
 * Composes in nested position (a field, {@code Map} value, or collection element), with a section at the
 * value's sub-path — see {@link br.com.finalcraft.everyconfig.binding.ConfigLifecycle} for the rules.
 *
 * @see br.com.finalcraft.everyconfig.binding.ConfigLifecycle
 * @see br.com.finalcraft.everyconfig.binding.ConfigContext
 * @see PreLoad
 * @see PostLoad
 * @see PostSave
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PreSave {
}
