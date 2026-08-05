package br.com.finalcraft.everyconfig.ruleset.data;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleHandler;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.rule.RuleViolation;

import java.util.Collections;
import java.util.List;

/** The handler behind {@link NoSpaces}. */
public final class NoSpacesHandler implements RuleHandler {

    @Override
    public void check(final RuleContext context) {
        final Object value = context.value();
        if (value instanceof CharSequence && value.toString().indexOf(' ') >= 0) {
            context.report().violation(RuleViolation.of(context.site(), context.source(), value,
                    "everyconfig.test.nospaces", Collections.emptyList(), "must not contain a space"));
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("No spaces.");
    }
}
