package br.com.finalcraft.everyconfig.ruleset.jakarta;

import br.com.finalcraft.everyconfig.rule.RuleContext;
import br.com.finalcraft.everyconfig.rule.RuleEngine;
import br.com.finalcraft.everyconfig.rule.RuleHandler;
import br.com.finalcraft.everyconfig.rule.RuleSelector;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.jakarta.logic.AssertFalseHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.logic.AssertTrueHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.number.DecimalMaxHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.number.DecimalMinHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.number.MaxHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.number.MinHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.number.NegativeHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.number.NegativeOrZeroHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.number.PositiveHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.number.PositiveOrZeroHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.presence.NotBlankHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.presence.NotEmptyHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.presence.NotNullHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.presence.NullHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.size.DigitsHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.size.SizeHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.temporal.FutureHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.temporal.FutureOrPresentHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.temporal.PastHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.temporal.PastOrPresentHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.text.EmailHandler;
import br.com.finalcraft.everyconfig.ruleset.jakarta.text.PatternHandler;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads {@code jakarta.validation.constraints} natively: the annotations, and nothing else. No provider, no
 * {@code ServiceLoader}, no {@code Validator} — a vocabulary this good already exists, so it is understood
 * rather than reinvented. Another library on the classpath carrying a different version of the same
 * annotations is harmless, because these are only ever read.
 *
 * <p><b>Membership is by class reference, not package prefix.</b> That makes the claim exact — a future Bean
 * Validation constraint with no handler here is NOT claimed, so it stays visible to another engine — and it
 * survives shading, since a relocated class reference relocates with the constraint it points at.
 *
 * <p><b>Declared divergences from Bean Validation.</b> {@code null} passes every constraint except
 * {@code @NotNull}/{@code @NotEmpty}/{@code @NotBlank} ({@code @Null} demands it), so presence and content
 * stay separate rules composed by whoever declares them. The range constraints accept EVERY numeric type,
 * {@code double} and {@code float} included, comparing in {@code BigDecimal}. There are no groups, no
 * payload, no cascade: the descent into nested types is always on. Each handler documents its own message
 * key arguments; a {@code message()} written by hand replaces the English text, while Bean Validation's
 * own {@code {key}} template does not.
 */
public final class JakartaRules {

    /** The constraints this module honors, each with the handler that judges it. */
    private static final Map<Class<? extends Annotation>, RuleHandler> HANDLERS = handlers();

    /**
     * The exact constraint set honored here — the introspection surface for a consumer that wants to know
     * what will fire before anything binds.
     */
    public static final Set<Class<? extends Annotation>> SUPPORTED =
            Collections.unmodifiableSet(HANDLERS.keySet());

    /** Claims exactly {@link #SUPPORTED}, so a constraint with no handler here is left for someone else. */
    public static final RuleSelector SELECTOR = annotation -> HANDLERS.containsKey(annotation.annotationType());

    private static final RuleEngine ENGINE = new JakartaEngine();

    private JakartaRules() {
    }

    /** The engine that applies the honored constraints; stateless, so the one instance serves every config. */
    public static RuleEngine engine() {
        return ENGINE;
    }

    private static Map<Class<? extends Annotation>, RuleHandler> handlers() {
        final Map<Class<? extends Annotation>, RuleHandler> map = new LinkedHashMap<>();
        map.put(NotNull.class, new NotNullHandler());
        map.put(Null.class, new NullHandler());
        map.put(NotBlank.class, new NotBlankHandler());
        map.put(NotEmpty.class, new NotEmptyHandler());
        map.put(Size.class, new SizeHandler());
        map.put(Digits.class, new DigitsHandler());
        map.put(Min.class, new MinHandler());
        map.put(Max.class, new MaxHandler());
        map.put(DecimalMin.class, new DecimalMinHandler());
        map.put(DecimalMax.class, new DecimalMaxHandler());
        map.put(Positive.class, new PositiveHandler());
        map.put(PositiveOrZero.class, new PositiveOrZeroHandler());
        map.put(Negative.class, new NegativeHandler());
        map.put(NegativeOrZero.class, new NegativeOrZeroHandler());
        map.put(Pattern.class, new PatternHandler());
        map.put(Email.class, new EmailHandler());
        map.put(AssertTrue.class, new AssertTrueHandler());
        map.put(AssertFalse.class, new AssertFalseHandler());
        map.put(Past.class, new PastHandler());
        map.put(PastOrPresent.class, new PastOrPresentHandler());
        map.put(Future.class, new FutureHandler());
        map.put(FutureOrPresent.class, new FutureOrPresentHandler());
        return Collections.unmodifiableMap(map);
    }

    /** Dispatch by annotation type: a constraint outside {@link #SUPPORTED} never reaches here, because the
     *  bind shortlists through {@link #SELECTOR} first. */
    private static final class JakartaEngine implements RuleEngine {

        @Override
        public RuleSelector selector() {
            return SELECTOR;
        }

        @Override
        public void apply(final RuleContext context) {
            final RuleHandler handler = HANDLERS.get(context.site().rule().annotationType());
            if (handler != null) {
                handler.check(context);
            }
        }

        @Override
        public List<String> describe(final RuleSite site) {
            final RuleHandler handler = HANDLERS.get(site.rule().annotationType());
            return handler != null ? handler.describe(site) : Collections.<String>emptyList();
        }
    }
}
