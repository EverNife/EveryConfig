package br.com.finalcraft.everyconfig.ruleset.jakarta.presence;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleHandler;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.support.Sizes;
import br.com.finalcraft.everyconfig.ruleset.support.TargetTypes;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;

import java.util.Collections;
import java.util.List;

/** {@code @NotEmpty}: at least one character, entry or element; null fails too. No arguments. */
public final class NotEmptyHandler implements RuleHandler {

    @Override
    public void check(final RuleContext context) {
        TargetTypes.require(context.site(), TargetTypes.isSized(context.site().valueType()),
                "text, a collection, a map or an array");
        final Object value = context.value();
        if (value == null || Sizes.lengthOf(value) == 0) {
            Violations.report(context, "must not be empty");
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("Must not be empty.");
    }
}
