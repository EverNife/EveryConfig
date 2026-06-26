package br.com.finalcraft.finalconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Renames a field's on-disk key and/or applies a case transform to it. With an empty {@link #value()}
 * the field's own name is used as the base and only {@link #transformCase()} is applied; with a
 * non-empty value that literal key is used (after the case transform). Equivalent to Jackson's
 * {@code @JsonProperty} for the rename, with the added case transform Jackson has no concept of.
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Key {

    String value() default "";

    KeyTransformCase transformCase() default KeyTransformCase.NONE;
}
