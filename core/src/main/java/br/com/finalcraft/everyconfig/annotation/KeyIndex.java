package br.com.finalcraft.everyconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the field whose value indexes a collection of these entities: the collection serializes as a map
 * keyed by this field's value (the key becomes the section name), and the field itself is omitted from
 * each entity body because the section key already carries it. Exactly one per class. It applies to a
 * collection written by path and to a {@code Collection}/array field of a bean alike.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface KeyIndex {
}
