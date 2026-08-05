package br.com.finalcraft.everyconfig.ruleset.jakarta.number;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/** {@code @DecimalMin}: a lower bound written as an exact decimal string, inclusive or not. Arguments: the
 *  bound, then whether it is inclusive. */
public final class DecimalMinHandler extends NumericBound {

    @Override
    void judge(final RuleContext context, final BigDecimal value) {
        final DecimalMin rule = (DecimalMin) context.site().rule();
        final int comparison = value.compareTo(new BigDecimal(rule.value()));
        if (rule.inclusive() ? comparison < 0 : comparison <= 0) {
            Violations.report(context, "must be " + (rule.inclusive() ? "at least " : "greater than ")
                    + rule.value(), rule.value(), rule.inclusive());
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        final DecimalMin rule = (DecimalMin) site.rule();
        return Collections.singletonList((rule.inclusive() ? "At least " : "Greater than ")
                + rule.value() + ".");
    }
}
