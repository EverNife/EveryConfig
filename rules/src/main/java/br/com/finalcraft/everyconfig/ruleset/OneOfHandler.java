package br.com.finalcraft.everyconfig.ruleset;

import br.com.finalcraft.everyconfig.binding.BindException;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Judges {@link OneOf}: every value has to be in the accepted set. On a collection or an array the check is
 * per element, so a file with three bad entries produces three violations, each naming its own entry.
 *
 * <p>Message arguments: the accepted set as it stood at the moment of the check, then whether case was
 * ignored.
 */
public final class OneOfHandler implements RuleHandler {

    /** One provider instance per class, shared: it is asked for its values on every evaluation, so building
     *  it once is the only part that is safe to cache. */
    private static final ConcurrentHashMap<Class<? extends OneOfSource>, OneOfSource> PROVIDERS =
            new ConcurrentHashMap<>();

    @Override
    public void check(final RuleContext context) {
        final Class<?> type = context.site().valueType();
        TargetTypes.require(context.site(),
                TargetTypes.isCharSequence(type) || Collection.class.isAssignableFrom(type) || type.isArray(),
                "text, a collection of text or an array of text");
        final Object value = context.value();
        if (value == null) {
            return; // absence is @NotNull's business
        }
        final OneOf rule = (OneOf) context.site().rule();
        final List<String> accepted = acceptedValues(rule);
        for (final Object element : elementsOf(value)) {
            if (element != null && !accepts(accepted, element.toString(), rule.ignoreCase())) {
                Violations.reportValue(context, element, "must be one of " + render(accepted) + ", but is '"
                        + element + "'", accepted, rule.ignoreCase());
            }
        }
    }

    @Override
    public List<String> describe(final RuleSite site) {
        final OneOf rule = (OneOf) site.rule();
        final List<String> declared = new ArrayList<>();
        Collections.addAll(declared, rule.value());
        // Only the DECLARED set is rendered: a provider's set changes between runs, and a comment that
        // changes with it would re-dirty the file on every save.
        if (declared.isEmpty()) {
            return rule.provider() == OneOfSource.None.class ? Collections.<String>emptyList()
                    : Collections.singletonList("One of a set resolved at runtime.");
        }
        final String tail = rule.provider() == OneOfSource.None.class ? "."
                : ", plus values resolved at runtime.";
        return Collections.singletonList("One of: " + render(declared) + tail);
    }

    /** The declared values and the provider's, in that order — a union, so a static core and a dynamic tail
     *  can be declared together. */
    private static List<String> acceptedValues(final OneOf rule) {
        final List<String> accepted = new ArrayList<>();
        Collections.addAll(accepted, rule.value());
        if (rule.provider() != OneOfSource.None.class) {
            final Collection<String> supplied = providerFor(rule.provider()).values();
            if (supplied != null) {
                accepted.addAll(supplied);
            }
        }
        return accepted;
    }

    private static boolean accepts(final List<String> accepted, final String candidate,
                                   final boolean ignoreCase) {
        for (final String option : accepted) {
            if (ignoreCase ? candidate.equalsIgnoreCase(option) : candidate.equals(option)) {
                return true;
            }
        }
        return false;
    }

    /** The values to judge: the whole value for text, each entry for a collection or an array. */
    private static Iterable<?> elementsOf(final Object value) {
        if (value instanceof Collection) {
            return (Collection<?>) value;
        }
        if (value.getClass().isArray()) {
            final List<Object> elements = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                elements.add(Array.get(value, i));
            }
            return elements;
        }
        return Collections.singletonList(value);
    }

    private static String render(final List<String> accepted) {
        final StringBuilder out = new StringBuilder();
        for (final String option : accepted) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(option.isEmpty() ? "''" : option);
        }
        return out.toString();
    }

    private static OneOfSource providerFor(final Class<? extends OneOfSource> type) {
        OneOfSource found = PROVIDERS.get(type);
        if (found == null) {
            found = instantiate(type);
            PROVIDERS.putIfAbsent(type, found);
        }
        return found;
    }

    private static OneOfSource instantiate(final Class<? extends OneOfSource> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (final Exception failed) {
            throw new BindException("@OneOf provider " + type.getName() + " could not be instantiated: "
                    + failed.getMessage() + ". One instance is created and shared, so a provider needs an "
                    + "accessible no-argument constructor and must read its values in values(), not in its "
                    + "constructor.", failed);
        }
    }
}
