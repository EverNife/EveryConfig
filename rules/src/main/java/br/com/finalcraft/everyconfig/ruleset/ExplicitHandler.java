package br.com.finalcraft.everyconfig.ruleset;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleHandler;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.rule.RuleViolation;
import br.com.finalcraft.everyconfig.rule.ValueSource;

import java.util.Collections;
import java.util.List;

/** Judges {@link Explicit}: the key has to have been in the file. No message arguments. */
public final class ExplicitHandler implements RuleHandler {

    @Override
    public void check(final RuleContext context) {
        if (context.source() == ValueSource.FILE) {
            return;
        }
        // Always default-sourced - that IS the rule - so the policy's escalation of default violations would
        // fail every first run, before the seeded file exists. Treat it the way file data is treated instead.
        context.report().violation(RuleViolation
                .of(context.site(), context.source(), context.value(), "everyconfig.rule.explicit",
                        Collections.emptyList(),
                        "must be set in the config file; the value in use is the built-in default")
                .withSeverity(context.severityFor(ValueSource.FILE)));
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("Must be set explicitly in this file.");
    }
}
