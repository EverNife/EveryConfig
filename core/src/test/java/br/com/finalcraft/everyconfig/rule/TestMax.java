package br.com.finalcraft.everyconfig.rule;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An upper bound that actually fires, so the bind suites have a rule with an outcome instead of a marker.
 * Public because the codec-agnostic contract lives in another package and declares fields carrying it.
 */
@ConfigRule(TestMaxHandler.class)
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TestMax {

    /** The largest accepted value; unbounded by default, so a declared bound always reads back as declared. */
    int value() default Integer.MAX_VALUE;

    /** What a violation is corrected to; {@link Integer#MIN_VALUE} leaves the value alone. */
    int correctTo() default Integer.MIN_VALUE;

    /** Whether the handler stamps {@link #severity()} on its violation instead of letting the policy decide. */
    boolean stamped() default false;

    RulePolicy.Severity severity() default RulePolicy.Severity.REPORT;
}
