package br.com.finalcraft.everyconfig.ruleset.jakarta.number;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;
import jakarta.validation.constraints.DecimalMax;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/** {@code @DecimalMax}: an upper bound written as an exact decimal string, inclusive or not. Arguments: the
 *  bound, then whether it is inclusive. */
public final class DecimalMaxHandler extends NumericBound {

    @Override
    void judge(final RuleContext context, final BigDecimal value) {
        final DecimalMax rule = (DecimalMax) context.site().rule();
        final int comparison = value.compareTo(new BigDecimal(rule.value()));
        if (rule.inclusive() ? comparison > 0 : comparison >= 0) {
            Violations.report(context, "must be " + (rule.inclusive() ? "at most " : "less than ")
                    + rule.value(), rule.value(), rule.inclusive());
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        final DecimalMax rule = (DecimalMax) site.rule();
        return Collections.singletonList((rule.inclusive() ? "At most " : "Less than ") + rule.value() + ".");
    }
}
