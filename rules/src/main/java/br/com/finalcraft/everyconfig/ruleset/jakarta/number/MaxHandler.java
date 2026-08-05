package br.com.finalcraft.everyconfig.ruleset.jakarta.number;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;
import jakarta.validation.constraints.Max;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/** {@code @Max}: an inclusive upper bound, on any numeric type. Argument: the bound. */
public final class MaxHandler extends NumericBound {

    @Override
    void judge(final RuleContext context, final BigDecimal value) {
        final long bound = ((Max) context.site().rule()).value();
        if (value.compareTo(BigDecimal.valueOf(bound)) > 0) {
            Violations.report(context, "must be at most " + bound, bound);
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("At most " + ((Max) site.rule()).value() + ".");
    }
}
