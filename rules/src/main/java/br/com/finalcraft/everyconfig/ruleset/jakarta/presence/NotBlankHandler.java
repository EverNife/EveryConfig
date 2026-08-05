package br.com.finalcraft.everyconfig.ruleset.jakarta.presence;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleHandler;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.support.Sizes;
import br.com.finalcraft.everyconfig.ruleset.support.TargetTypes;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;

import java.util.Collections;
import java.util.List;

/** {@code @NotBlank}: text with at least one non-whitespace character; null and {@code "   "} both fail. No
 *  arguments. */
public final class NotBlankHandler implements RuleHandler {

    @Override
    public void check(final RuleContext context) {
        TargetTypes.require(context.site(), TargetTypes.isCharSequence(context.site().valueType()), "text");
        final Object value = context.value();
        if (value == null) {
            Violations.report(context, "must not be blank");
            return;
        }
        if (Sizes.isBlank((CharSequence) value)) {
            Violations.report(context, "must not be blank");
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("Must not be blank.");
    }
}
