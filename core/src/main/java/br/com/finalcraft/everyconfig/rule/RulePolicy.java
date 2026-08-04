package br.com.finalcraft.everyconfig.rule;

import org.jetbrains.annotations.Nullable;

/**
 * What a bind does with a rule violation — the third axis of the design: the FACT is declared on the field,
 * the CONSUMER is attached at the point of use, and the POLICY is chosen per config. Immutable; derive a
 * variant with the {@code with...} methods.
 *
 * <p>The two origins are deliberately separate. A value that came from the FILE is user data: reporting it
 * and letting the config load is usually right. A value that came from the entity's own DEFAULT is a code
 * defect — no file can fix it and every run reproduces it — so it throws out of the box.
 */
public final class RulePolicy {

    /** What a violation costs. */
    public enum Severity {

        /** It becomes a load issue and the bind continues; the caller decides what that is worth. */
        REPORT,

        /** As {@link #REPORT}, plus a warning logged once per site. */
        LOG,

        /** The bind fails on the first violation. */
        THROW
    }

    private static final RulePolicy DEFAULTS = new RulePolicy(null, Severity.THROW, false);

    private final Severity severity;
    private final Severity defaultViolations;
    private final boolean applyCorrections;

    private RulePolicy(final Severity severity, final Severity defaultViolations,
                       final boolean applyCorrections) {
        this.severity = severity;
        this.defaultViolations = defaultViolations;
        this.applyCorrections = applyCorrections;
    }

    /** No declared severity for file data (the bind's coercion decides), throwing on a default-sourced
     *  violation, corrections off. */
    public static RulePolicy defaults() {
        return DEFAULTS;
    }

    /** The severity for a FILE-sourced violation; null derives it from the bind's coercion (strict throws,
     *  lenient reports), so a config that already chose how strict it is does not choose twice. */
    @Nullable
    public Severity severity() {
        return severity;
    }

    /** The severity for a DEFAULT-sourced violation — the entity violating its own rule. Never null. */
    public Severity defaultViolations() {
        return defaultViolations;
    }

    /** Whether {@link RuleContext#correct(Object)} takes effect; off by default, so reading never rewrites. */
    public boolean applyCorrections() {
        return applyCorrections;
    }

    public RulePolicy withSeverity(@Nullable final Severity severity) {
        return new RulePolicy(severity, defaultViolations, applyCorrections);
    }

    public RulePolicy withDefaultViolations(final Severity severity) {
        if (severity == null) {
            throw new IllegalArgumentException("defaultViolations cannot be null; pass REPORT to downgrade it");
        }
        return new RulePolicy(this.severity, severity, applyCorrections);
    }

    public RulePolicy withCorrections(final boolean apply) {
        return new RulePolicy(severity, defaultViolations, apply);
    }

    @Override
    public String toString() {
        return "RulePolicy[severity=" + severity + ", defaultViolations=" + defaultViolations
                + ", corrections=" + applyCorrections + "]";
    }
}
