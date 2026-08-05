package br.com.finalcraft.everyconfig.rule;

/**
 * One violation a standalone evaluation found, already decided: what was broken, what it costs, and the
 * text that teaches the way out. Immutable.
 *
 * <p>A bind turns its violations into {@code LoadIssue}s and acts on them itself; a caller evaluating a
 * value of its own has no bind to act for it, so the decision comes back here instead.
 */
public final class RuleFinding {

    private final RuleViolation violation;
    private final RulePolicy.Severity severity;
    private final String message;

    RuleFinding(final RuleViolation violation, final RulePolicy.Severity severity, final String message) {
        this.violation = violation;
        this.severity = severity;
        this.message = message;
    }

    /** What was violated, where, and by which value. */
    public RuleViolation violation() {
        return violation;
    }

    /** What it costs: the severity the engine stamped on the violation, else the policy's answer for where
     *  the value came from. Never null — the caller is the one who acts on it. */
    public RulePolicy.Severity severity() {
        return severity;
    }

    /** The English explanation, in the wording a bind failure uses: what was rejected, and what to change
     *  so it is not. Localize from {@link RuleViolation#messageKey()} instead when you have translations. */
    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return severity + ": " + message;
    }
}
