package br.com.finalcraft.everyconfig.ruleset.jakarta.logic;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleHandler;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.support.TargetTypes;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;

import java.util.Collections;
import java.util.List;

/**
 * {@code @AssertTrue}: the value has to be true. No arguments.
 *
 * <p>On a no-argument method it is the cross-field invariant: the method computes the answer from the whole
 * entity and the constraint states what the answer must be. Pair such a method with {@code @JsonIgnore}, or
 * the mapper writes it into the file as a key of its own.
 */
public final class AssertTrueHandler implements RuleHandler {

    @Override
    public void check(final RuleContext context) {
        TargetTypes.require(context.site(), TargetTypes.isBoolean(context.site().valueType()), "a boolean");
        final Object value = context.value();
        if (value != null && !((Boolean) value)) {
            Violations.report(context, "must be true");
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("Must be true.");
    }
}
