package br.com.finalcraft.everyconfig.ruleset.jakarta.number;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/** {@code @PositiveOrZero}: zero or greater. No arguments. */
public final class PositiveOrZeroHandler extends NumericBound {

    @Override
    void judge(final RuleContext context, final BigDecimal value) {
        if (value.signum() < 0) {
            Violations.report(context, "must be 0 or greater");
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("0 or greater.");
    }
}
