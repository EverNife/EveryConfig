package br.com.finalcraft.everyconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the read half of a type's compact element form (see {@link EveryConfigCompactValue}): either a
 * {@code public static} factory taking a single {@code String} and returning the type, or a constructor taking
 * a single {@code String}. It is invoked to rebuild a value from a compact element string.
 *
 * <p>Jackson does NOT act on this annotation, so it does not disturb the type's normal (rich) binding — a value
 * read solo/as a field still goes through the mapper.
 */
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.RUNTIME)
public @interface EveryConfigCompactCreator {
}
