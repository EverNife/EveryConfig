package br.com.finalcraft.everyconfig.ruleset;

import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleEvaluator;
import br.com.finalcraft.everyconfig.rule.RuleHandler;
import br.com.finalcraft.everyconfig.rule.RuleModel;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.rule.RuleViolation;
import br.com.finalcraft.everyconfig.rule.ValueSource;
import br.com.finalcraft.everyconfig.ruleset.support.TargetTypes;

import java.util.Collections;
import java.util.List;

/**
 * Judges {@link Explicit}: the key has to have been in the file. No message arguments.
 *
 * <p>It also refuses the one declaration the annotation cannot be part of - see
 * {@link #requireASeedWorthWriting}.
 */
public final class ExplicitHandler implements RuleHandler {

    @Override
    public void check(final RuleContext context) {
        requireASeedWorthWriting(context);
        if (context.source() == ValueSource.FILE) {
            return;
        }
        // Always default-sourced - that IS the rule - so the policy's escalation of default violations would
        // fail every first run, before the seeded file exists. Treat it the way file data is treated instead.
        context.report().violation(RuleViolation
                .of(context.site(), context.source(), context.value(), "everyconfig.rule.explicit",
                        Collections.emptyList(),
                        "must be set in the config file; the value in use is the built-in default")
                .withSeverity(context.severityFor(ValueSource.FILE)));
    }

    /**
     * A fresh file is seeded with the member's own default, and {@link Explicit} tells the operator to
     * overwrite exactly that. A content rule beside it that refuses the default therefore describes a
     * declaration with no legal first run: nothing valid can be written, and the operator is handed a file
     * that is already broken. That is a defect in the CODE, so it fails on every application rather than only
     * on the run where the key still happens to be missing.
     *
     * <p>Only a neighbour the ATTACHED engine claims counts. An annotation whose engine this config never
     * attached never rejects anything here either, so the seed it would have refused is one this config
     * happily writes.
     */
    private static void requireASeedWorthWriting(final RuleContext context) {
        final RuleSite explicit = context.site();
        if (explicit.field() == null) {
            return;
        }
        // Corrections stay off (the default policy): a seed is judged, never rewritten.
        final RuleEvaluator seedJudge = RuleEvaluator.of(context.engine());
        for (final RuleSite sibling : RuleModel.of(explicit.owner(), context.engine().selector())) {
            // By equality, not identity: the sibling was resolved from the declaring class and the @Explicit
            // site may have been resolved from an entity that merely nests it, which is a separate walk.
            if (!explicit.field().equals(sibling.field())
                    || sibling.rule().annotationType() == Explicit.class) {
                continue;
            }
            final Object seed = sibling.defaultValue();
            if (seed == null) {
                // A type that cannot be constructed without data and a genuinely null default answer the
                // same here, and refusing a declaration on that guess is worse than missing one.
                continue;
            }
            if (seedJudge.evaluate(sibling, context.section(), seed, ValueSource.DEFAULT, context.owner())
                    .findings().isEmpty()) {
                continue;
            }
            final String rule = "@" + sibling.rule().annotationType().getSimpleName();
            throw new BindException("@Explicit on " + TargetTypes.member(explicit) + " ('" + explicit.path()
                    + "') sits beside " + rule + ", which refuses the field's own default '" + seed
                    + "'. @Explicit asks the operator to overwrite that default in the file, and that same "
                    + "default is what a fresh file is seeded with - so there is no value to write and no "
                    + "first run that can pass. Give the field a default " + rule + " accepts, or drop one "
                    + "of the two annotations.");
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("Must be set explicitly in this file.");
    }
}
