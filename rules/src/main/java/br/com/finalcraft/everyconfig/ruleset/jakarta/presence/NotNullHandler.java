package br.com.finalcraft.everyconfig.ruleset.jakarta.presence;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleHandler;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;

import java.util.Collections;
import java.util.List;

/** {@code @NotNull}: the value has to be there. No arguments. */
public final class NotNullHandler implements RuleHandler {

    @Override
    public void check(final RuleContext context) {
        if (context.value() == null) {
            Violations.report(context, "must not be null");
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("Required.");
    }
}
