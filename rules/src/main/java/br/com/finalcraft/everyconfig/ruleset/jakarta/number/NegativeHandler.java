package br.com.finalcraft.everyconfig.ruleset.jakarta.number;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/** {@code @Negative}: strictly less than zero. No arguments. */
public final class NegativeHandler extends NumericBound {

    @Override
    void judge(final RuleContext context, final BigDecimal value) {
        if (value.signum() >= 0) {
            Violations.report(context, "must be less than 0");
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("Less than 0.");
    }
}
