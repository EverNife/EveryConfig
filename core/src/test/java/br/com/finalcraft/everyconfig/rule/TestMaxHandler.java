package br.com.finalcraft.everyconfig.rule;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Rejects a number above the declared bound, optionally clamping it first — the shape a real handler has:
 *  it corrects and still reports, so what changed stays visible. */
public final class TestMaxHandler implements RuleHandler {

    @Override
    public void check(final RuleContext context) {
        final TestMax rule = (TestMax) context.site().rule();
        final Object value = context.value();
        if (!(value instanceof Number) || ((Number) value).doubleValue() <= rule.value()) {
            return;
        }
        if (rule.correctTo() != Integer.MIN_VALUE) {
            context.correct(rule.correctTo());
        }
        RuleViolation violation = RuleViolation.of(context.site(), context.source(), value,
                "everyconfig.test.max", Arrays.<Object>asList(rule.value()),
                "must be at most " + rule.value());
        if (rule.stamped()) {
            violation = violation.withSeverity(rule.severity());
        }
        context.report().violation(violation);
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("at most " + ((TestMax) site.rule()).value());
    }
}
