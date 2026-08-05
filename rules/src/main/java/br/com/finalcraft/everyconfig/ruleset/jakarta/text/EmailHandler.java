package br.com.finalcraft.everyconfig.ruleset.jakarta.text;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleHandler;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.support.Regexes;
import br.com.finalcraft.everyconfig.ruleset.support.TargetTypes;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;
import jakarta.validation.constraints.Email;

import java.util.Collections;
import java.util.List;

/**
 * {@code @Email}: the text has to look like an e-mail address, and additionally match the constraint's own
 * {@code regexp} when one is given. Argument: the additional expression.
 *
 * <p>The shape checked here is pragmatic, not RFC 5322: a local part with no whitespace and no {@code @},
 * one {@code @}, and a domain with at least one dot. Bean Validation deliberately fixes no expression, so
 * differing from another provider is expected — this one is stated rather than guessed at. An empty value
 * passes, as it does under Bean Validation; require presence with {@code @NotBlank}.
 */
public final class EmailHandler implements RuleHandler {

    private static final String SHAPE = "[^\\s@]+@[^\\s@]+\\.[^\\s@]+";

    @Override
    public void check(final RuleContext context) {
        TargetTypes.require(context.site(), TargetTypes.isCharSequence(context.site().valueType()), "text");
        final Object value = context.value();
        if (value == null || ((CharSequence) value).length() == 0) {
            return;
        }
        final Email rule = (Email) context.site().rule();
        final CharSequence text = (CharSequence) value;
        final boolean valid = Regexes.matches(SHAPE, 0, text)
                && Regexes.matches(rule.regexp(), PatternHandler.flagsOf(rule.flags()), text);
        if (!valid) {
            Violations.report(context, "must be a well-formed e-mail address", rule.regexp());
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("Must be an e-mail address.");
    }
}
