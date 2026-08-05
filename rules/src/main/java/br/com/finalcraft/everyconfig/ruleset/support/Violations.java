package br.com.finalcraft.everyconfig.ruleset.support;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleViolation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

/**
 * Builds the violation a jakarta constraint reports, so every handler produces the same shape: the key is
 * derived from the constraint's own name and the English text is a fallback, not the last word.
 */
public final class Violations {

    private Violations() {
    }

    /** Report a violation of the constraint at the context's site, with the ordered arguments of its key. */
    public static void report(final RuleContext context, final String english, final Object... args) {
        reportValue(context, context.value(), english, args);
    }

    /** As {@link #report(RuleContext, String, Object...)} but naming a value that is not the whole site's —
     *  the single offending element of a collection, which is what the reader has to be pointed at. */
    public static void reportValue(final RuleContext context, final Object actualValue, final String english,
                                   final Object... args) {
        final Annotation rule = context.site().rule();
        context.report().violation(RuleViolation.of(context.site(), context.source(), actualValue,
                messageKey(rule), Arrays.asList(args), message(rule, english)));
    }

    /** The stable localization key of a constraint: {@code everyconfig.rule.max} for {@code @Max}. */
    public static String messageKey(final Annotation rule) {
        return "everyconfig.rule." + rule.annotationType().getSimpleName().toLowerCase(Locale.ROOT);
    }

    /**
     * The text this violation carries: whatever the author wrote in {@code message()}, falling back to
     * {@code english} when they left the constraint's own default in place. Bean Validation's defaults are
     * resource-bundle templates ({@code {jakarta.validation.constraints.Max.message}}), which say nothing to
     * a reader of a config file — but a hand-written message is the author explaining their own rule, and
     * that always outranks the library's wording.
     */
    public static String message(final Annotation rule, final String english) {
        final String declared = declaredMessage(rule);
        if (declared == null) {
            return english;
        }
        final String trimmed = declared.trim();
        if (trimmed.isEmpty() || (trimmed.startsWith("{") && trimmed.endsWith("}"))) {
            return english;
        }
        return trimmed;
    }

    private static String declaredMessage(final Annotation rule) {
        try {
            final Method message = rule.annotationType().getMethod("message");
            final Object value = message.invoke(rule);
            return value instanceof String ? (String) value : null;
        } catch (final Exception noMessageAttribute) {
            return null;
        }
    }
}
