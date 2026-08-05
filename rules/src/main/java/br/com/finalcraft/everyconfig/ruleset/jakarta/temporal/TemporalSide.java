package br.com.finalcraft.everyconfig.ruleset.jakarta.temporal;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleHandler;
import br.com.finalcraft.everyconfig.ruleset.support.TargetTypes;
import br.com.finalcraft.everyconfig.ruleset.support.Temporals;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;

/**
 * The shape the four temporal constraints share: the site must carry a temporal type, a null passes, and
 * the value is placed on one side of now — each type against its own {@code now()}, with no tolerance
 * window. A type outside the declared surface is a declaration defect and fails on first application.
 */
abstract class TemporalSide implements RuleHandler {

    @Override
    public final void check(final RuleContext context) {
        TargetTypes.require(context.site(), Temporals.isSupported(context.site().valueType()),
                "a date, a time or an instant");
        final Object value = context.value();
        if (value == null) {
            return;
        }
        final Integer position = Temporals.compareToNow(value);
        if (position != null && !accepts(position)) {
            Violations.report(context, "must be " + expectation());
        }
    }

    /** Whether a value sitting at {@code position} relative to now (negative past, zero now, positive
     *  future) satisfies this constraint. */
    abstract boolean accepts(int position);

    /** How the message names the side that IS accepted. */
    abstract String expectation();
}
