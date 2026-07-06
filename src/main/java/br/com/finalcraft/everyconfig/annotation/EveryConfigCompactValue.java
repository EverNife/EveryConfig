package br.com.finalcraft.everyconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the instance method that produces a type's COMPACT one-line form for use as a collection element
 * (a no-arg method returning a {@code String}). The type keeps its rich solo/field form (owned by the mapper)
 * untouched — this form is used ONLY when the value is an element of a list written through the dynamic path
 * ({@code setValue}/{@code getList}). Pair it with {@link EveryConfigCompactCreator} for the read half.
 *
 * <p>Jackson does NOT act on this annotation (it is not a Jackson annotation); only EveryConfig's compact
 * element path reads it, via {@code AnnotationCompactElementResolver}. For a type you cannot annotate, attach a
 * {@code CompactElementResolver} to the codec instead.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EveryConfigCompactValue {
}
