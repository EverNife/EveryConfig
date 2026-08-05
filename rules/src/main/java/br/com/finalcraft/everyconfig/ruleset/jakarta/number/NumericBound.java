package br.com.finalcraft.everyconfig.ruleset.jakarta.number;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleHandler;
import br.com.finalcraft.everyconfig.ruleset.support.Numbers;
import br.com.finalcraft.everyconfig.ruleset.support.TargetTypes;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;

import java.math.BigDecimal;

/**
 * The shape every numeric constraint shares: it must sit on a number, a null passes (absence is
 * {@code @NotNull}'s business), and what is judged is one exact decimal.
 *
 * <p>A value with no place on the number line — {@code NaN}, an infinity — satisfies no bound, so it is
 * reported by every one of them with the constraint's own key and NO arguments; the bound itself never
 * entered the comparison.
 */
abstract class NumericBound implements RuleHandler {

    @Override
    public final void check(final RuleContext context) {
        TargetTypes.require(context.site(), TargetTypes.isNumeric(context.site().valueType()), "a number");
        final Object value = context.value();
        if (value == null) {
            return;
        }
        final BigDecimal number = Numbers.decimalOf(value);
        if (number == null) {
            Violations.report(context, "must be a comparable number, but is " + value);
            return;
        }
        judge(context, number);
    }

    /** Judge {@code value} — never null, always comparable — against this constraint's bound. */
    abstract void judge(RuleContext context, BigDecimal value);
}
