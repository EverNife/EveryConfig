package br.com.finalcraft.everyconfig.ruleset.jakarta.size;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleHandler;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.support.Sizes;
import br.com.finalcraft.everyconfig.ruleset.support.TargetTypes;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;
import jakarta.validation.constraints.Size;

import java.util.Collections;
import java.util.List;

/** {@code @Size}: how many characters, entries or elements are allowed. Arguments: min, max, actual size. */
public final class SizeHandler implements RuleHandler {

    @Override
    public void check(final RuleContext context) {
        TargetTypes.require(context.site(), TargetTypes.isSized(context.site().valueType()),
                "text, a collection, a map or an array");
        final Object value = context.value();
        if (value == null) {
            return; // absence is @NotNull's business; a size constraint judges what IS there
        }
        final Size rule = (Size) context.site().rule();
        final int length = Sizes.lengthOf(value);
        if (length < rule.min() || length > rule.max()) {
            Violations.report(context, "size must be " + bounds(rule) + ", but is " + length,
                    rule.min(), rule.max(), length);
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        final Size rule = (Size) site.rule();
        if (rule.min() == 0 && rule.max() == Integer.MAX_VALUE) {
            return Collections.emptyList();
        }
        return Collections.singletonList("Size " + bounds(rule) + ".");
    }

    private static String bounds(final Size rule) {
        if (rule.min() > 0 && rule.max() < Integer.MAX_VALUE) {
            return "between " + rule.min() + " and " + rule.max();
        }
        if (rule.min() > 0) {
            return "at least " + rule.min();
        }
        return "at most " + rule.max();
    }
}
