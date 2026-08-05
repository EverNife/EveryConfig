package br.com.finalcraft.everyconfig.ruleset.support;

import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.rule.RuleSite;

import java.util.Collection;
import java.util.Map;

/**
 * What each constraint may be declared on, and the failure when it is not.
 *
 * <p>A constraint on a type it cannot judge — {@code @Size} on an {@code int} — is a defect in the
 * DECLARATION, not in the data: no config file can satisfy or break it, and every run reproduces it. So it
 * fails on the first application with a message naming the member, the constraint and the types that would
 * work, rather than passing quietly and leaving a rule that never fires.
 */
public final class TargetTypes {

    private TargetTypes() {
    }

    public static boolean isNumeric(final Class<?> type) {
        return Number.class.isAssignableFrom(type)
                || type == byte.class || type == short.class || type == int.class || type == long.class
                || type == float.class || type == double.class;
    }

    public static boolean isCharSequence(final Class<?> type) {
        return CharSequence.class.isAssignableFrom(type);
    }

    /** CharSequence, Collection, Map or an array — everything that answers "how many". */
    public static boolean isSized(final Class<?> type) {
        return CharSequence.class.isAssignableFrom(type)
                || Collection.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type)
                || type.isArray();
    }

    public static boolean isBoolean(final Class<?> type) {
        return type == boolean.class || type == Boolean.class;
    }

    /** Fail unless the site's declared type is one this constraint can judge. */
    public static void require(final RuleSite site, final boolean accepted, final String validTargets) {
        if (accepted) {
            return;
        }
        throw new BindException("@" + site.rule().annotationType().getSimpleName() + " on " + member(site)
                + " ('" + site.path() + "') cannot be applied to " + site.valueType().getSimpleName()
                + ". It accepts " + validTargets + " - move the constraint to a member of one of those types, "
                + "or remove it.");
    }

    private static String member(final RuleSite site) {
        if (site.field() != null) {
            return site.owner().getSimpleName() + "." + site.field().getName();
        }
        if (site.method() != null) {
            return site.owner().getSimpleName() + "." + site.method().getName() + "()";
        }
        return site.owner().getSimpleName();
    }
}
