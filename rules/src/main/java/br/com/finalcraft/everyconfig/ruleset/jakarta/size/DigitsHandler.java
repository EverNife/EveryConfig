package br.com.finalcraft.everyconfig.ruleset.jakarta.size;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleHandler;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.support.Numbers;
import br.com.finalcraft.everyconfig.ruleset.support.TargetTypes;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/** {@code @Digits}: how many digits fit before and after the decimal point. Arguments: integer, fraction,
 *  actual integer digits, actual decimal places. */
public final class DigitsHandler implements RuleHandler {

    @Override
    public void check(final RuleContext context) {
        final Class<?> type = context.site().valueType();
        TargetTypes.require(context.site(), TargetTypes.isNumeric(type) || TargetTypes.isCharSequence(type),
                "a number or text holding one");
        final Object value = context.value();
        if (value == null) {
            return;
        }
        final Digits rule = (Digits) context.site().rule();
        final BigDecimal number = Numbers.decimalOfNumberOrText(value);
        if (number == null) {
            Violations.report(context, "must be a number with at most " + rule.integer() + " integer digits "
                    + "and " + rule.fraction() + " decimal places", rule.integer(), rule.fraction(), -1, -1);
            return;
        }
        final BigDecimal trimmed = number.stripTrailingZeros();
        final int integerDigits = trimmed.precision() - trimmed.scale();
        final int fractionDigits = Math.max(trimmed.scale(), 0);
        if (integerDigits > rule.integer() || fractionDigits > rule.fraction()) {
            Violations.report(context, "must have at most " + rule.integer() + " integer digits and "
                            + rule.fraction() + " decimal places, but has " + integerDigits + " and "
                            + fractionDigits,
                    rule.integer(), rule.fraction(), integerDigits, fractionDigits);
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        final Digits rule = (Digits) site.rule();
        return Collections.singletonList("At most " + rule.integer() + " integer digits and "
                + rule.fraction() + " decimal places.");
    }
}
