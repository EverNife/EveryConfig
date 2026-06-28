package br.com.finalcraft.everyconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to run right BEFORE an entity is written (merged) into the tree, so it can compute or
 * scrub fields before they are serialized. Note this fires on the POJO-to-tree write, which precedes the
 * file flush ({@code Config.save}), not the flush itself. Takes no parameters. Overridden methods run once.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PreSave {
}
