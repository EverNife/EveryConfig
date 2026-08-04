package br.com.finalcraft.everyconfig.rule;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A vocabulary EveryConfig does not own — no {@code @ConfigRule} marker, exactly like a validation API it
 *  cannot annotate. It stands in for such an API here so the core suite needs no third-party dependency. */
@Target({ElementType.FIELD, ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface PlainMark {

    String value() default "";
}
