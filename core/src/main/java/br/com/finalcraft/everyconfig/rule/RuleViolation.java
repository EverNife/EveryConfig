package br.com.finalcraft.everyconfig.rule;

import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One rule violation: what was wrong, where, and — only when the engine wants to outrank the policy — how
 * much it costs. Immutable; {@link #withSeverity} derives a stamped copy.
 *
 * <p>The message comes in two shapes so a consumer can localize without the library shipping translations:
 * a stable {@link #messageKey()} plus ordered {@link #messageArgs()}, and an already-formatted English
 * {@link #defaultMessage()} to fall back on.
 */
public final class RuleViolation {

    /** The message key of a violation the entity raised itself, which has no annotation behind it. */
    public static final String ENTITY_MESSAGE_KEY = "everyconfig.rule.entity";

    private final String path;
    private final Annotation rule;
    private final Object actualValue;
    private final ValueSource source;
    private final String messageKey;
    private final List<Object> messageArgs;
    private final String defaultMessage;
    private final RulePolicy.Severity severity;

    private RuleViolation(final String path, final Annotation rule, final Object actualValue,
                          final ValueSource source, final String messageKey, final List<Object> messageArgs,
                          final String defaultMessage, final RulePolicy.Severity severity) {
        this.path = path;
        this.rule = rule;
        this.actualValue = actualValue;
        this.source = source;
        this.messageKey = messageKey;
        this.messageArgs = messageArgs;
        this.defaultMessage = defaultMessage;
        this.severity = severity;
    }

    /** A violation of the rule declared at {@code site}, reported at the site's path. */
    public static RuleViolation of(final RuleSite site, final ValueSource source,
                                   @Nullable final Object actualValue, final String messageKey,
                                   final List<Object> messageArgs, final String defaultMessage) {
        return new RuleViolation(site.path(), site.rule(), actualValue, source, messageKey,
                immutableArgs(messageArgs), defaultMessage, null);
    }

    /** A violation the entity raised out of its own logic while reviewing: no annotation behind it, so
     *  {@link #rule()} is null and the message carries the whole explanation. */
    public static RuleViolation ofEntity(final String path, final ValueSource source,
                                         @Nullable final Object actualValue, final String defaultMessage) {
        return new RuleViolation(path, null, actualValue, source, ENTITY_MESSAGE_KEY,
                Collections.<Object>emptyList(), defaultMessage, null);
    }

    /** A copy reported at {@code path} instead — what an evaluation outside a bind needs, where the value
     *  lives wherever its caller put it and not where the binder's path grammar would have. */
    RuleViolation withPath(final String path) {
        return new RuleViolation(path, rule, actualValue, source, messageKey, messageArgs, defaultMessage,
                severity);
    }

    /** A copy carrying an engine-declared severity, which outranks the policy; null hands the decision back
     *  to the policy. */
    public RuleViolation withSeverity(@Nullable final RulePolicy.Severity severity) {
        return new RuleViolation(path, rule, actualValue, source, messageKey, messageArgs, defaultMessage,
                severity);
    }

    /** The dotted FILE path of the offending value — the site's path. */
    public String path() {
        return path;
    }

    /** The annotation that was violated; null when the entity raised the violation itself. */
    @Nullable
    public Annotation rule() {
        return rule;
    }

    @Nullable
    public Object actualValue() {
        return actualValue;
    }

    public ValueSource source() {
        return source;
    }

    /** A stable localization key, e.g. {@code "everyconfig.rule.max"}. */
    public String messageKey() {
        return messageKey;
    }

    /** The ordered arguments of {@link #messageKey()}; immutable. */
    public List<Object> messageArgs() {
        return messageArgs;
    }

    /** The formatted English message, for a consumer with nothing to localize with. */
    public String defaultMessage() {
        return defaultMessage;
    }

    /** The severity the engine declared for this violation; null lets the policy decide. */
    @Nullable
    public RulePolicy.Severity severity() {
        return severity;
    }

    private static List<Object> immutableArgs(final List<Object> args) {
        return args == null || args.isEmpty() ? Collections.<Object>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(args));
    }

    @Override
    public String toString() {
        return (rule == null ? "entity" : "@" + rule.annotationType().getSimpleName())
                + " at '" + path + "' (" + source + "): " + defaultMessage;
    }
}
