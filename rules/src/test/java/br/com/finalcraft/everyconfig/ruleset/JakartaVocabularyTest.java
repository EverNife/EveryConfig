package br.com.finalcraft.everyconfig.ruleset;

import br.com.finalcraft.everyconfig.rule.RuleModel;
import br.com.finalcraft.everyconfig.rule.RuleSite;
import br.com.finalcraft.everyconfig.ruleset.data.RulesetDtos;
import br.com.finalcraft.everyconfig.ruleset.jakarta.JakartaRules;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the module CLAIMS, independent of any bind: the constraint set, the selectors built on it, and the
 * text each constraint documents itself with. A claim that drifts from the handler map is the failure this
 * catches — a constraint claimed with nothing behind it would be silently inert.
 */
@DisplayName("Jakarta vocabulary")
class JakartaVocabularyTest {

    /** The constraints honored here, written out — so adding a handler without deciding to publish it, or
     *  publishing one without a handler, fails instead of drifting. */
    private static final List<Class<? extends Annotation>> HONORED = Arrays.asList(
            NotNull.class, Null.class, NotBlank.class, NotEmpty.class,
            Size.class, Digits.class,
            Min.class, Max.class, DecimalMin.class, DecimalMax.class,
            Positive.class, PositiveOrZero.class, Negative.class, NegativeOrZero.class,
            Pattern.class, Email.class,
            AssertTrue.class, AssertFalse.class,
            Past.class, PastOrPresent.class, Future.class, FutureOrPresent.class);

    @Test
    @DisplayName("the honored set is exactly the 22 constraints, by class reference")
    void supportedSetIsExact() {
        assertEquals(22, HONORED.size(), "the list in this test IS the expectation");
        assertEquals(new HashSet<>(HONORED), new HashSet<>(JakartaRules.SUPPORTED));
    }

    @Test
    @DisplayName("the selector claims every honored constraint and nothing else")
    void selectorClaimsExactlyTheHonoredSet() {
        for (final RuleSite site : RuleModel.of(EveryConstraint.class, annotation -> true)) {
            final Class<? extends Annotation> type = site.rule().annotationType();
            assertTrue(JakartaRules.SELECTOR.claims(site.rule()),
                    "expected the jakarta selector to claim " + type.getName());
        }
        for (final RuleSite site : RuleModel.of(RulesetDtos.VocabularyDto.class, StandardRules.EVERYCONFIG)) {
            assertFalse(JakartaRules.SELECTOR.claims(site.rule()),
                    "the jakarta selector must not claim " + site.rule().annotationType().getName());
        }
    }

    @Test
    @DisplayName("every honored constraint documents itself with one deterministic line")
    void everyConstraintDescribesItself() {
        final List<String> described = new ArrayList<>();
        for (final RuleSite site : RuleModel.of(EveryConstraint.class, JakartaRules.SELECTOR)) {
            final List<String> lines = JakartaRules.engine().describe(site);
            assertEquals(1, lines.size(), "expected one line for "
                    + site.rule().annotationType().getSimpleName() + ", got " + lines);
            assertEquals(lines, JakartaRules.engine().describe(site), "describe must be deterministic");
            described.add(lines.get(0));
        }
        assertTrue(described.contains("At most 65535."), described.toString());
        assertTrue(described.contains("Must be an e-mail address."), described.toString());
        assertTrue(described.contains("Size between 2 and 5."), described.toString());
        assertTrue(described.contains("At most 3 integer digits and 2 decimal places."), described.toString());
    }

    @Test
    @DisplayName("a @Size with no bound at all documents nothing rather than an empty sentence")
    void unboundedSizeDescribesNothing() {
        final List<RuleSite> sites = RuleModel.of(UnboundedSize.class, JakartaRules.SELECTOR);
        assertEquals(1, sites.size());
        assertEquals(Collections.emptyList(), JakartaRules.engine().describe(sites.get(0)));
    }

    /** One field per honored constraint, so introspection sees all 22 without binding anything. */
    static class EveryConstraint {

        @NotNull
        @Null
        String presence;

        @NotBlank
        String text = "x";

        @NotEmpty
        @Size(min = 2, max = 5)
        List<String> entries = new ArrayList<>();

        @Digits(integer = 3, fraction = 2)
        @Min(1)
        @Max(65535)
        @DecimalMin("0.5")
        @DecimalMax("10")
        @Positive
        @PositiveOrZero
        @Negative
        @NegativeOrZero
        int number = 1;

        @Pattern(regexp = "[a-z]+")
        @Email
        String address = "a@b.co";

        @AssertTrue
        @AssertFalse
        boolean flag;

        @Past
        @PastOrPresent
        @Future
        @FutureOrPresent
        LocalDate when = LocalDate.now();
    }

    static class UnboundedSize {

        @Size
        String anything = "";
    }
}
