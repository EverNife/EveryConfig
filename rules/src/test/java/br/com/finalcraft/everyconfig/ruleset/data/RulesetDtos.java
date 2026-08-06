package br.com.finalcraft.everyconfig.ruleset.data;

import br.com.finalcraft.everyconfig.annotation.Comment;
import br.com.finalcraft.everyconfig.annotation.Section;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.rule.RuleReview;
import br.com.finalcraft.everyconfig.rule.RuleReviewContext;
import br.com.finalcraft.everyconfig.rule.RuleReviewer;
import br.com.finalcraft.everyconfig.rule.RuleViolation;
import br.com.finalcraft.everyconfig.ruleset.Explicit;
import br.com.finalcraft.everyconfig.ruleset.OneOf;
import br.com.finalcraft.everyconfig.ruleset.OneOfSource;
import br.com.finalcraft.everyconfig.ruleset.Unique;
import com.fasterxml.jackson.annotation.JsonIgnore;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The entities the ruleset suites bind. Every default is a value that PASSES its own constraint, so a
 * violation in a test is always the one the test seeded — a default that broke its own rule would throw
 * before the case under test was even reached.
 */
public final class RulesetDtos {

    private RulesetDtos() {
    }

    /** Every numeric constraint at once. */
    public static class NumericDto {

        @Min(1)
        @Max(65535)
        public int port = 25565;

        /** The showcase: a percentage bounded on a double, which Bean Validation would not allow. */
        @Min(0)
        @Max(100)
        public double dropChance = 25.0;

        @DecimalMin("0.5")
        public BigDecimal ratio = new BigDecimal("1.0");

        @DecimalMax(value = "10", inclusive = false)
        public double belowTen = 5.0;

        @Positive
        public int positive = 1;

        @PositiveOrZero
        public int positiveOrZero = 0;

        @Negative
        public int negative = -1;

        @NegativeOrZero
        public int negativeOrZero = 0;

        @Digits(integer = 3, fraction = 2)
        public BigDecimal price = new BigDecimal("12.34");

        /** Absent from every fixture file: the wrapper stays null, and a bound must let it through. */
        @Max(10)
        public Integer optionalMax;
    }

    /** Text, presence and size, on the four target shapes {@code @Size} accepts. */
    public static class TextDto {

        @NotBlank
        public String name = "srv";

        @Size(min = 2, max = 5)
        public String code = "abc";

        @Pattern(regexp = "[a-z-]+")
        public String slug = "main-world";

        @Email
        public String contact = "ops@example.com";

        @NotEmpty
        public List<String> worlds = new ArrayList<>(Arrays.asList("world"));

        @Size(max = 2)
        public Map<String, String> aliases = new LinkedHashMap<>();

        @Size(min = 1)
        public int[] slots = {1, 2};

        /** Absent from every fixture file, so it is null and {@code @Null} is satisfied. */
        @Null
        public String reserved;

        /** Absent from every fixture file: a bound has to fire only when the file supplies a value. */
        @NotNull
        @Size(max = 3)
        public String required = "abc";
    }

    /** The four temporal constraints, each on a different temporal type. */
    public static class TemporalDto {

        @Past
        public LocalDate started = LocalDate.now().minusDays(1);

        @Future
        public LocalDate expires = LocalDate.now().plusDays(1);

        @PastOrPresent
        public Instant seen = Instant.now().minusSeconds(60);

        @FutureOrPresent
        public LocalDateTime due = LocalDateTime.now().plusDays(1);
    }

    /** The cross-field invariant, written the way it is written in Spring: an asserting getter. */
    public static class RangeDto {

        public int min = 1;

        public int max = 10;

        @JsonIgnore
        @AssertTrue(message = "'min' must not be greater than 'max'")
        public boolean isRangeValid() {
            return min <= max;
        }

        @JsonIgnore
        @AssertFalse
        public boolean isInverted() {
            return min > max;
        }
    }

    /** The three annotations EveryConfig adds. */
    public static class VocabularyDto {

        @Explicit
        @NotBlank
        public String token = "changeme";

        @OneOf(value = {"MONGO", "SQL", ""}, ignoreCase = true)
        public String dbType = "";

        @OneOf({"alpha", "beta"})
        public List<String> stages = new ArrayList<>(Arrays.asList("alpha"));

        @Unique
        public List<String> worlds = new ArrayList<>(Arrays.asList("world"));
    }

    /** A {@code @OneOf} whose set only exists while the program runs. */
    public static class DynamicOneOfDto {

        @OneOf(value = {"static"}, provider = LoadedWorlds.class)
        public String world = "static";
    }

    /** The runtime set behind {@link DynamicOneOfDto}; a test swaps it between binds. */
    public static final class LoadedWorlds implements OneOfSource {

        public static final AtomicReference<List<String>> CURRENT =
                new AtomicReference<>(new ArrayList<String>());

        @Override
        public Collection<String> values() {
            return CURRENT.get();
        }
    }

    /** A field documented BOTH by hand and by its rules, for the composed-comment contract. */
    public static class DocumentedDto {

        @Comment("Port the service binds to.")
        @Min(1)
        @Max(65535)
        public int port = 25565;

        /** Documented only by its rule, so the composed text is the rule text alone. */
        @Unique
        public List<String> worlds = new ArrayList<>(Arrays.asList("world"));
    }

    /** A sectioned, nested entity: the rule's path has to be where the VALUE lands, not where it is declared. */
    public static class NestedDto {

        @Section("server.network")
        @Min(1)
        @Max(65535)
        public int port = 25565;

        public Pool pool = new Pool();

        public static class Pool {

            @Min(1)
            public int size = 4;
        }
    }

    /** The magic word: a value the annotation cannot list, accepted by the entity's own review. */
    public static class MagicWordDto implements RuleReviewer {

        @OneOf({"MONGO", "SQL"})
        public String dbType = "SQL";

        @Override
        public void reviewRules(final RuleReviewContext review) {
            for (final RuleViolation violation : review.violations()) {
                if (violation.rule() instanceof OneOf
                        && "$auto".equalsIgnoreCase(String.valueOf(violation.actualValue()))) {
                    review.accept(violation); // resolved at runtime; the set could never have listed it
                }
            }
        }
    }

    /** One field claimed by three different vocabularies at once: jakarta's, EveryConfig's own, and a
     *  consumer's. */
    public static class MixedVocabularyDto {

        @Size(max = 2)
        @OneOf({"MONGO", "SQL"})
        @NoSpaces
        public String dbType = "SQL";
    }

    /** A constraint on a type it cannot judge — a declaration defect, not bad data. */
    public static class BadTargetDto {

        @Size(min = 1)
        public int count = 3;
    }

    /** A value the operator has to write down, seeded with one the rule beside it refuses. */
    public static class UnseedableDto {

        @Explicit
        @NotBlank
        public String token = "";
    }

    /** A bound the entity itself clamps instead of only complaining about. */
    public static class ClampingReviewDto {

        @Max(100)
        public int limit = 10;

        @RuleReview
        void clampToTheBound(final RuleReviewContext review) {
            for (final RuleViolation violation : review.violations()) {
                if (violation.rule() instanceof Max) {
                    review.correct(violation, 100);
                }
            }
        }
    }

    /** No candidate annotation anywhere: the whole subsystem has to be invisible to it. */
    public static class PlainDto {

        public String name = "";

        public int count = 0;
    }

    /** The cross-config check: what makes the value invalid is declared in ANOTHER file. */
    public static class DatabaseRefDto {

        /** The storage config this entity checks against; wiring it is the consumer's job, so a test plays
         *  the consumer. */
        public static final AtomicReference<Config> STORAGE = new AtomicReference<>();

        public String databaseId = "";

        @RuleReview
        void databaseMustBeEnabled(final RuleReviewContext review) {
            final Config storage = STORAGE.get();
            if (storage != null && !storage.getBoolean("databases." + databaseId + ".enabled", false)) {
                review.fail("databaseId", "storage declares '" + databaseId + "' as disabled; pick an "
                        + "enabled database or enable it there");
            }
        }
    }
}
