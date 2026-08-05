package br.com.finalcraft.everyconfig.ruleset.jakarta.logic;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleHandler;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.support.TargetTypes;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;

import java.util.Collections;
import java.util.List;

/** {@code @AssertFalse}: the value has to be false — the twin of {@code @AssertTrue}, method sites included.
 *  No arguments. */
public final class AssertFalseHandler implements RuleHandler {

    @Override
    public void check(final RuleContext context) {
        TargetTypes.require(context.site(), TargetTypes.isBoolean(context.site().valueType()), "a boolean");
        final Object value = context.value();
        if (value != null && ((Boolean) value)) {
            Violations.report(context, "must be false");
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("Must be false.");
    }
}
