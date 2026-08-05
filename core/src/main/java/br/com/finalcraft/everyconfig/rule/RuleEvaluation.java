package br.com.finalcraft.everyconfig.rule;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * What evaluating one site produced: what the rules found, and the value that came out the other side.
 * Immutable.
 */
public final class RuleEvaluation {

    private final List<RuleFinding> findings;
    private final Object value;
    private final boolean corrected;

    RuleEvaluation(final List<RuleFinding> findings, final Object value, final boolean corrected) {
        this.findings = findings.isEmpty() ? Collections.<RuleFinding>emptyList()
                : Collections.unmodifiableList(findings);
        this.value = value;
        this.corrected = corrected;
    }

    /** What the rules found, in the order the engine reported it; empty when the value was accepted. */
    public List<RuleFinding> findings() {
        return findings;
    }

    /** The value that survived: what a handler corrected it to, or the value that was judged when nothing
     *  corrected it. A correction has already been written into the field, so a caller that keeps its own
     *  copy of the value has to take this one. */
    @Nullable
    public Object value() {
        return value;
    }

    /** Whether a handler rewrote the value — which it can only do when the policy allows corrections. */
    public boolean corrected() {
        return corrected;
    }

    @Override
    public String toString() {
        return "RuleEvaluation[findings=" + findings.size() + ", corrected=" + corrected + "]";
    }
}
