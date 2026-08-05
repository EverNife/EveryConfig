package br.com.finalcraft.everyconfig.ruleset.jakarta.number;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/** {@code @Min}: an inclusive lower bound, on any numeric type. Argument: the bound. */
public final class MinHandler extends NumericBound {

    @Override
    void judge(final RuleContext context, final BigDecimal value) {
        final long bound = ((Min) context.site().rule()).value();
        if (value.compareTo(BigDecimal.valueOf(bound)) < 0) {
            Violations.report(context, "must be at least " + bound, bound);
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("At least " + ((Min) site.rule()).value() + ".");
    }
}
