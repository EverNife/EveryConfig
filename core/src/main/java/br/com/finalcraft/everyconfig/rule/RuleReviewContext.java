package br.com.finalcraft.everyconfig.rule;

import br.com.finalcraft.everyconfig.config.section.ConfigSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a reviewing entity sees and can decide, after the engines ran on its sites and before the policy
 * acts on what they found.
 *
 * <p>Review outranks everything: {@link #accept} suppresses a violation the engine stamped THROW, and
 * {@link #override} re-stamps one the policy would have merely reported. The entity is the last word on its
 * own data, which is what makes a runtime exception to a declared rule expressible — a value no annotation
 * could list, an invariant that depends on another config.
 *
 * <p>Scope is one instance: the violations here are the ones raised on THIS instance's own sites, and a
 * decision about any other violation is ignored. A nested entity reviews its own; the root does not see
 * theirs. The last decision on a violation wins.
 */
public final class RuleReviewContext {

    private final RulePhase phase;
    private final ConfigSection section;
    private final List<RuleViolation> violations;

    /** The violation each slot was opened with — the identity a decision is addressed to, unchanged by an
     *  override that replaced the slot's current value. */
    private final List<RuleViolation> keys;

    /** The outcome of each slot: the violation as it now stands, or null once accepted. */
    private final List<RuleViolation> outcome;

    private final List<Correction> corrections = new ArrayList<>();

    RuleReviewContext(final RulePhase phase, final ConfigSection section,
                      final List<RuleViolation> violations) {
        this.phase = phase;
        this.section = section;
        this.violations = Collections.unmodifiableList(new ArrayList<>(violations));
        this.keys = new ArrayList<>(violations);
        this.outcome = new ArrayList<>(violations);
    }

    public RulePhase phase() {
        return phase;
    }

    /** A section at THIS instance's path: its siblings, the tree, and the owning config. */
    public ConfigSection section() {
        return section;
    }

    /** The engines' findings on this instance's own sites — an immutable snapshot taken before any decision. */
    public List<RuleViolation> violations() {
        return violations;
    }

    /** Suppress {@code violation} entirely: no issue, no log, no throw. A correction already requested for it
     *  still stands — accepting hides the report, it does not undo the fix. */
    public void accept(final RuleViolation violation) {
        final int slot = slotOf(violation);
        if (slot >= 0) {
            outcome.set(slot, null);
        }
    }

    /** Re-stamp {@code violation}'s severity, up or down, outranking both the engine's stamp and the policy. */
    public void override(final RuleViolation violation, final RulePolicy.Severity severity) {
        final int slot = slotOf(violation);
        if (slot >= 0) {
            outcome.set(slot, keys.get(slot).withSeverity(severity));
        }
    }

    /** Fix the site behind {@code violation} to {@code newValue}. Unlike an engine's correction this is not
     *  gated by the policy — it is the author's explicit intent, not an automatic rewrite — and the violation
     *  stays reported unless it is also accepted. */
    public void correct(final RuleViolation violation, @Nullable final Object newValue) {
        if (slotOf(violation) >= 0) {
            corrections.add(new Correction(violation, newValue));
        }
    }

    /** Raise a violation of the entity's own logic at {@code relativePath} (relative to this instance), for
     *  the policy to weigh like any other. */
    public RuleViolation report(final String relativePath, final String defaultMessage) {
        return raise(relativePath, defaultMessage, null);
    }

    /** As {@link #report}, stamped THROW: the bind fails on it. */
    public RuleViolation fail(final String relativePath, final String defaultMessage) {
        return raise(relativePath, defaultMessage, RulePolicy.Severity.THROW);
    }

    /** The violations that survived review, in order: the surviving originals as they now stand, then the
     *  ones the entity raised. */
    List<RuleViolation> decided() {
        final List<RuleViolation> out = new ArrayList<>(outcome.size());
        for (final RuleViolation v : outcome) {
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    /** The corrections the review asked for, in the order it asked. */
    List<Correction> corrections() {
        return corrections;
    }

    private RuleViolation raise(final String relativePath, final String defaultMessage,
                                final RulePolicy.Severity severity) {
        final boolean present = section.contains(relativePath);
        RuleViolation raised = RuleViolation.ofEntity(section.concatSubPath(relativePath),
                present ? ValueSource.FILE : ValueSource.DEFAULT,
                present ? section.getValue(relativePath) : null, defaultMessage);
        if (severity != null) {
            raised = raised.withSeverity(severity);
        }
        keys.add(raised);
        outcome.add(raised);
        return raised;
    }

    /** The slot a decision addresses, matched by identity so an override does not detach the original from
     *  its slot; -1 when the violation belongs to another instance. */
    private int slotOf(final RuleViolation violation) {
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i) == violation) {
                return i;
            }
        }
        return -1;
    }

    /** A value the review wants written at a violation's site. */
    static final class Correction {

        private final RuleViolation violation;
        private final Object newValue;

        Correction(final RuleViolation violation, final Object newValue) {
            this.violation = violation;
            this.newValue = newValue;
        }

        RuleViolation violation() {
            return violation;
        }

        Object newValue() {
            return newValue;
        }
    }
}
