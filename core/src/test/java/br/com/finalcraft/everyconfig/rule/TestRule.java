package br.com.finalcraft.everyconfig.rule;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A rule of the built-in vocabulary: {@code @ConfigRule}-marked and repeatable, so both the marker filter
 *  and the occurrence order of a repeated declaration can be asserted. */
@ConfigRule(TestRuleHandler.class)
@Repeatable(TestRule.List.class)
@Target({ElementType.FIELD, ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TestRule {

    String value() default "";

    /** The container the compiler folds repeated declarations into. Public because a Java 8 runtime cannot
     *  read a package-private one — it fails the whole repeatable lookup with an AnnotationFormatError. */
    @Target({ElementType.FIELD, ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface List {

        TestRule[] value();
    }
}
