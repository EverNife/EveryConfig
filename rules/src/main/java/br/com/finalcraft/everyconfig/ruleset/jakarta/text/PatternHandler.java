package br.com.finalcraft.everyconfig.ruleset.jakarta.text;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleHandler;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.support.Regexes;
import br.com.finalcraft.everyconfig.ruleset.support.TargetTypes;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;
import jakarta.validation.constraints.Pattern;

import java.util.Collections;
import java.util.List;

/** {@code @Pattern}: the text has to match a regular expression, end to end. Argument: the expression. */
public final class PatternHandler implements RuleHandler {

    @Override
    public void check(final RuleContext context) {
        TargetTypes.require(context.site(), TargetTypes.isCharSequence(context.site().valueType()), "text");
        final Object value = context.value();
        if (value == null) {
            return; // absence is @NotNull's business
        }
        final Pattern rule = (Pattern) context.site().rule();
        if (!Regexes.matches(rule.regexp(), flagsOf(rule.flags()), (CharSequence) value)) {
            Violations.report(context, "must match '" + rule.regexp() + "'", rule.regexp());
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("Must match: " + ((Pattern) site.rule()).regexp());
    }

    /** The declared flags folded into the bit mask the regex engine takes. */
    public static int flagsOf(final Pattern.Flag[] flags) {
        int mask = 0;
        for (final Pattern.Flag flag : flags) {
            mask |= flag.getValue();
        }
        return mask;
    }
}
