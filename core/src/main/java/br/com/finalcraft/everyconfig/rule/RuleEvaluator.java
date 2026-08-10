package br.com.finalcraft.everyconfig.rule;

import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.config.section.ConfigSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Judges one declared rule against a value the caller already holds — the bind's rule pass without the
 * bind. It exists for the reader that is not an entity: a scanner that owns its own field walk and its own
 * key grammar, a screen that validates what a user just typed, a value assembled in memory.
 *
 * <p>Everything the bind decides from the tree is a parameter here, because outside a bind nobody else
 * knows it: WHERE the value lives ({@code at}, which also carries the config a handler may look around
 * in), WHAT it is, WHERE it came from ({@link ValueSource}) and WHICH instance declares the field. The
 * site itself still comes from {@link RuleModel}, so what is evaluated is always a rule someone declared.
 *
 * <p>It never throws for a violation: the severity comes back on the finding and the caller — who owns the
 * log, the screen or the boot it would abort — decides what that is worth. A handler that fails while
 * judging is a different matter and still throws, because a broken rule is a defect, not user data.
 *
 * <p>Two parts of a bind are deliberately absent. {@code @RuleReview} does not run: a review reads every
 * violation of its instance at once, which only a caller that evaluated every site can offer. And a
 * correction is written into the field but never into the config tree — there is no projection here to
 * carry it, so persisting it stays the caller's decision.
 *
 * <p>Stateless and thread-safe; hold one per engine+policy pair and evaluate with it as often as needed.
 */
public final class RuleEvaluator {

    private final RuleEngine engine;
    private final RulePolicy policy;

    private RuleEvaluator(final RuleEngine engine, final RulePolicy policy) {
        this.engine = engine;
        this.policy = policy;
    }

    /** An evaluator over {@code engine}, under the default policy. */
    public static RuleEvaluator of(final RuleEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("a RuleEvaluator needs an engine; pass RuleEngine.NONE to "
                    + "evaluate nothing, or StandardRules.engine() for the standard vocabulary");
        }
        return new RuleEvaluator(engine, RulePolicy.defaults());
    }

    /** A copy judging under {@code policy}. */
    public RuleEvaluator withPolicy(final RulePolicy policy) {
        return new RuleEvaluator(engine, policy != null ? policy : RulePolicy.defaults());
    }

    /** The engine — the same one whose {@link RuleEngine#selector() selector} shortlists the sites to
     *  evaluate and whose {@link RuleEngine#describe(RuleSite) describe} documents them. */
    public RuleEngine engine() {
        return engine;
    }

    public RulePolicy policy() {
        return policy;
    }

    /**
     * Judge {@code value}, which lives at {@code at} and belongs to {@code owner}, against the rule
     * declared at {@code site}.
     *
     * <p>The violations come back reported at {@code at}'s path rather than at the site's: a caller that
     * evaluates outside a bind put the value somewhere the binder's own path grammar never chose, and a
     * message naming a key the file does not have teaches nothing.
     *
     * <p>{@code owner} is the instance the rule may read its neighbours from, and the one a correction is
     * written into; pass null for a value that belongs to no instance yet — the value is still judged, and a
     * correction then only comes back on the evaluation.
     */
    public RuleEvaluation evaluate(final RuleSite site, final ConfigSection at, @Nullable final Object value,
                                   final ValueSource source, @Nullable final Object owner) {
        require(site != null, "site", "take one from RuleModel.of(type) - a rule someone declared is the only "
                + "thing there is to judge");
        require(at != null, "at", "a handler may look around the config from it, and the findings are reported "
                + "at its path; use config.getConfigSection(path)");
        require(source != null, "source", "ValueSource.FILE for a value someone supplied, ValueSource.DEFAULT "
                + "for one the code chose - a rule about provenance has no other way to tell");
        final List<RuleViolation> found = new ArrayList<>();
        final RuleContext context = new RuleContext(site, RulePhase.VALIDATE, value, owner, source, at,
                found::add, engine, policy, false);
        try {
            engine.apply(context);
        } catch (final BindException alreadyExplained) {
            throw alreadyExplained;
        } catch (final Exception failed) {
            throw new BindException("rule " + RuleMessages.describe(site.rule()) + " at '" + at.getPath()
                    + "' failed: " + failed.getMessage(), failed);
        }
        final List<RuleFinding> findings = new ArrayList<>();
        for (final RuleViolation raw : found) {
            final RuleViolation violation = raw.withPath(at.getPath());
            findings.add(new RuleFinding(violation, severityOf(violation),
                    RuleMessages.rejection(violation, site, site.owner())));
        }
        return new RuleEvaluation(findings, context.corrected() ? context.correctedValue() : value,
                context.corrected());
    }

    private static void require(final boolean satisfied, final String parameter, final String how) {
        if (!satisfied) {
            throw new IllegalArgumentException("RuleEvaluator.evaluate needs '" + parameter + "': " + how + ".");
        }
    }

    /** What a violation costs: the stamp the engine put on it wins, then the policy's answer for the
     *  value's origin. There is no bind to defer to here, so a policy that says "follow the coercion"
     *  gets the lenient answer. */
    private RulePolicy.Severity severityOf(final RuleViolation violation) {
        return violation.severity() != null ? violation.severity()
                : policy.severityFor(violation.source(), false);
    }
}
