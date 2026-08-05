package br.com.finalcraft.everyconfig.ruleset;

import br.com.finalcraft.everyconfig.rule.AnnotationRuleEngine;
import br.com.finalcraft.everyconfig.rule.RuleEngine;
import br.com.finalcraft.everyconfig.rule.RuleSelector;
import br.com.finalcraft.everyconfig.ruleset.jakarta.JakartaRules;

/**
 * The whole standard vocabulary in one place: jakarta's constraints plus {@link Explicit}, {@link OneOf} and
 * {@link Unique}.
 *
 * <pre>{@code
 * final Config config = Config.open(path, codec)
 *         .withRuleEngine(StandardRules.engine());
 * }</pre>
 *
 * <p>The selectors are exposed as constants so an introspection consumer — a screen builder, a scanner —
 * never hand-writes a package prefix, where a silent typo turns into an empty screen instead of an error.
 */
public final class StandardRules {

    /** The jakarta constraints this module honors. */
    public static final RuleSelector JAKARTA = JakartaRules.SELECTOR;

    /** The three annotations EveryConfig adds where jakarta has nothing to say. */
    public static final RuleSelector EVERYCONFIG = annotation ->
            annotation.annotationType() == Explicit.class
                    || annotation.annotationType() == OneOf.class
                    || annotation.annotationType() == Unique.class;

    private StandardRules() {
    }

    /**
     * The full standard vocabulary as one engine: the built-in annotation engine — which claims every
     * {@code @ConfigRule}-marked annotation, a consumer's own included — composed with the jakarta engine.
     *
     * <p>Attaching this REPLACES the engine a config carries by default and is a strict superset of it, so
     * nothing that worked before stops working. The two never claim the same annotation (a jakarta
     * constraint is never meta-marked), which makes the claim-based dispatch total.
     */
    public static RuleEngine engine() {
        return RuleEngine.compose(AnnotationRuleEngine.INSTANCE, JakartaRules.engine());
    }
}
