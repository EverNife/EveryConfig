package br.com.finalcraft.everyconfig.rule;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * How a rule violation reads. One wording for every way of finding it, so the same broken value explains
 * itself the same whether a bind or a standalone evaluation caught it.
 */
final class RuleMessages {

    private static final Comparator<Method> BY_NAME = new Comparator<Method>() {
        @Override
        public int compare(final Method left, final Method right) {
            return left.getName().compareTo(right.getName());
        }
    };

    private RuleMessages() {
    }

    /** The failure text, which has to teach the way out: bad file data says how to fix the file, and a
     *  default that breaks its own rule says why no file ever will. */
    static String rejection(final RuleViolation violation, final RuleSite site, final Class<?> entityType) {
        final String member = memberOf(site, entityType);
        if (violation.rule() == null) {
            return "Rule review at '" + violation.path() + "' rejects "
                    + (violation.source() == ValueSource.FILE ? "the file value '" : "the entity's own value '")
                    + violation.actualValue() + "'. " + violation.defaultMessage();
        }
        if (violation.source() == ValueSource.FILE) {
            return "Rule " + describe(violation.rule()) + " at '" + violation.path()
                    + "' rejects the file value '" + violation.actualValue()
                    + "'. Fix the value in the file, or relax the rule on " + member + ".";
        }
        if (violation.severity() != null) {
            // The engine chose this severity itself, so it already knows what an absent value means here -
            // a rule that fires BECAUSE the file is silent is asking for the file, not reporting a defect.
            return "Rule " + describe(violation.rule()) + " at '" + violation.path()
                    + "' rejects the value '" + violation.actualValue() + "' in use: "
                    + violation.defaultMessage() + ". Set it in the file, or relax the rule on " + member + ".";
        }
        return describe(violation.rule()) + " on " + member + " ('" + violation.path()
                + "') rejects the field's OWN DEFAULT value " + violation.actualValue()
                + ". This is a code defect, not user data: no config file can fix it, and every run "
                + "reproduces it. Change the field's initializer or relax the rule.";
    }

    static String memberOf(final RuleSite site, final Class<?> entityType) {
        if (site == null) {
            return entityType.getSimpleName();
        }
        if (site.field() != null) {
            return site.owner().getSimpleName() + "." + site.field().getName();
        }
        if (site.method() != null) {
            return site.owner().getSimpleName() + "." + site.method().getName() + "()";
        }
        return site.owner().getSimpleName();
    }

    /**
     * The annotation as it reads in source — {@code @Max(100)} — so a message names the constraint, not just
     * its kind. Only members that differ from their default are shown, sorted by name, and a lone
     * {@code value} is written bare.
     */
    static String describe(final Annotation rule) {
        if (rule == null) {
            return "review";
        }
        final String name = "@" + rule.annotationType().getSimpleName();
        final List<Method> members = new ArrayList<>();
        for (final Method member : rule.annotationType().getDeclaredMethods()) {
            if (member.getParameterCount() == 0 && !Modifier.isStatic(member.getModifiers())) {
                members.add(member);
            }
        }
        Collections.sort(members, BY_NAME);
        final List<String> names = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        for (final Method member : members) {
            final Object value;
            try {
                member.setAccessible(true);
                value = member.invoke(rule);
            } catch (final Exception unreadable) {
                continue;
            }
            if (Objects.deepEquals(value, member.getDefaultValue())) {
                continue;
            }
            names.add(member.getName());
            values.add(render(value));
        }
        if (names.isEmpty()) {
            return name;
        }
        if (names.size() == 1 && "value".equals(names.get(0))) {
            return name + "(" + values.get(0) + ")";
        }
        final StringBuilder out = new StringBuilder(name).append('(');
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(names.get(i)).append('=').append(values.get(i));
        }
        return out.append(')').toString();
    }

    private static String render(final Object value) {
        if (value == null || !value.getClass().isArray()) {
            return String.valueOf(value);
        }
        final StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < Array.getLength(value); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(render(Array.get(value, i)));
        }
        return out.append('}').toString();
    }
}
