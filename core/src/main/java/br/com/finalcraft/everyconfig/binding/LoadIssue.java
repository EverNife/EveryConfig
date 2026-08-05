package br.com.finalcraft.everyconfig.binding;

import br.com.finalcraft.everyconfig.rule.RuleViolation;
import org.jetbrains.annotations.Nullable;

/**
 * One thing a load found wrong at one path: a value that could not be bound to its target type, or a value
 * that bound fine but broke a declared rule. Recording it lets a single bad value degrade gracefully — the
 * field keeps a default, the load continues — instead of failing the whole config; a {@code @PostLoad}
 * validator inspects what was collected and decides whether that is acceptable.
 *
 * <p>Both kinds read the same: {@link #key()}, {@link #rawValue()} and {@link #message()} answer for either,
 * so code written before rules existed keeps working. {@link #kind()} tells them apart, and a rule issue
 * carries the whole {@link #violation()} for a caller that wants to localize it (a stable key plus ordered
 * arguments) rather than print the English fallback.
 */
public final class LoadIssue {

    /** What the load found wrong. */
    public enum Kind {

        /** The value could not be converted to the target type. */
        COERCION,

        /** The value converted, but a rule declared at that path rejected it. */
        RULE
    }

    private final String key;
    private final Object rawValue;
    private final Class<?> targetType;
    private final String message;
    private final Kind kind;
    private final RuleViolation violation;

    /** A value that would not convert to {@code targetType}. */
    public LoadIssue(final String key, final Object rawValue, final Class<?> targetType, final String message) {
        this(key, rawValue, targetType, message, Kind.COERCION, null);
    }

    /** A rule {@code violation}, reported at the path it names; {@code targetType} is the type of the value
     *  that was judged. */
    public LoadIssue(final RuleViolation violation, final Class<?> targetType) {
        this(violation.path(), violation.actualValue(), targetType, violation.defaultMessage(), Kind.RULE,
                violation);
    }

    private LoadIssue(final String key, final Object rawValue, final Class<?> targetType, final String message,
                      final Kind kind, final RuleViolation violation) {
        this.key = key;
        this.rawValue = rawValue;
        this.targetType = targetType;
        this.message = message;
        this.kind = kind;
        this.violation = violation;
    }

    public String key() {
        return key;
    }

    public Object rawValue() {
        return rawValue;
    }

    public Class<?> targetType() {
        return targetType;
    }

    public String message() {
        return message;
    }

    public Kind kind() {
        return kind;
    }

    /** The rule violation behind this issue; null unless {@link #kind()} is {@link Kind#RULE}. */
    @Nullable
    public RuleViolation violation() {
        return violation;
    }

    @Override
    public String toString() {
        if (kind == Kind.RULE) {
            return "'" + key + "' = '" + rawValue + "' violates "
                    + (violation.rule() == null ? "the entity's own review"
                                                : "@" + violation.rule().annotationType().getSimpleName())
                    + ": " + message;
        }
        return "'" + key + "' = '" + rawValue + "' (expected "
                + (targetType == null ? "?" : targetType.getSimpleName()) + "): " + message;
    }
}
