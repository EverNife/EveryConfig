package br.com.finalcraft.everyconfig.ruleset;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleHandler;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.support.TargetTypes;
import br.com.finalcraft.everyconfig.ruleset.support.Violations;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Judges {@link Unique}: the first entry that repeats is reported, with that entry as the offending value.
 * Message argument: the repeated entry.
 *
 * <p>Only the FIRST repeat is reported. A duplicated list is one defect with one fix — deduplicate it — and
 * one violation per extra copy would bury it.
 */
public final class UniqueHandler implements RuleHandler {

    @Override
    public void check(final RuleContext context) {
        final Class<?> type = context.site().valueType();
        TargetTypes.require(context.site(),
                Collection.class.isAssignableFrom(type) || type.isArray(), "a collection or an array");
        final Object value = context.value();
        if (value == null) {
            return;
        }
        final List<Object> seen = new ArrayList<>();
        for (final Object element : elementsOf(value)) {
            if (seen.contains(element)) { // contains() uses equals(), and matches null against null
                Violations.reportValue(context, element, "must not repeat an entry, but '" + element
                        + "' appears more than once", element);
                return;
            }
            seen.add(element);
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("No duplicate entries.");
    }

    private static Iterable<?> elementsOf(final Object value) {
        if (value instanceof Collection) {
            return (Collection<?>) value;
        }
        final List<Object> elements = new ArrayList<>();
        for (int i = 0; i < Array.getLength(value); i++) {
            elements.add(Array.get(value, i)); // boxes a primitive array, so int[] compares by value
        }
        return elements;
    }
}
