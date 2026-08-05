package br.com.finalcraft.everyconfig.ruleset;

import br.com.finalcraft.everyconfig.rule.ConfigRule;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The text has to be one of a known set. For a {@code Collection<String>} or a {@code String[]} the check is
 * per element, so each offending entry is reported on its own.
 *
 * <p>When the set is fixed at compile time, <b>an enum is still the right answer</b> — there the TYPE is the
 * rule and this annotation has no job. This exists for the set that is not fixed: a legacy {@code String}
 * field that cannot be migrated, or values only known while the program runs (see {@link #provider()}).
 *
 * <p>{@code null} passes; compose with {@code @NotNull} when the value is also required.
 */
@Documented
@ConfigRule(OneOfHandler.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OneOf {

    /** The accepted values, written out. */
    String[] value() default {};

    /** Supplies further accepted values while the program runs; with {@link #value()} the two are a UNION,
     *  so a static core and a dynamic tail can be declared together. */
    Class<? extends OneOfSource> provider() default OneOfSource.None.class;

    /** Whether case is ignored when matching — {@code "mongo"} against {@code "MONGO"} is real config
     *  friction, and cheaper to allow than to explain. */
    boolean ignoreCase() default false;
}
