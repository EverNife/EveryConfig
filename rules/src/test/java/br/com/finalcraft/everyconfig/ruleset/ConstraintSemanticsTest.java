package br.com.finalcraft.everyconfig.ruleset;

import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.binding.BindResult;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.rule.RulePolicy;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The semantics a file format has no say in: what a constraint accepts, on which shapes, and how the two
 * sources of a {@code @OneOf} set combine. Bound in memory, because the answer cannot differ per codec —
 * the codec-agnostic contract already proves the same rules travel through every emitter.
 */
@DisplayName("Constraint semantics")
class ConstraintSemanticsTest {

    /** A config with the standard vocabulary and a policy that REPORTS everything, so one test can look at
     *  several violations instead of stopping at the first. */
    private static Config reporting() {
        return Config.inMemory()
                .withRuleEngine(StandardRules.engine())
                .withRulePolicy(RulePolicy.defaults().withDefaultViolations(RulePolicy.Severity.REPORT));
    }

    private static List<String> messages(final BindResult<?> result) {
        final List<String> texts = new ArrayList<>();
        for (final LoadIssue issue : result.issues()) {
            texts.add(issue.key() + ": " + issue.message());
        }
        return texts;
    }

    // ===================== presence =====================

    static class PresenceDto {

        @Null
        public String mustBeAbsent;

        @NotBlank
        public String text;

        @NotEmpty
        public List<String> entries;
    }

    @Test
    @DisplayName("@Null rejects a value that IS there, while null satisfies it")
    void nullConstraintIsTheOneThatDemandsAbsence() {
        final Config c = reporting();
        c.setValue("mustBeAbsent", "something");
        final BindResult<PresenceDto> result = c.bind(PresenceDto.class).readResult("");

        assertTrue(messages(result).contains("mustBeAbsent: must not be set"), messages(result).toString());
    }

    @Test
    @DisplayName("@NotBlank and @NotEmpty are the constraints a null does NOT pass")
    void presenceConstraintsRejectNull() {
        final BindResult<PresenceDto> result = reporting().bind(PresenceDto.class).readResult("");

        assertEquals(Arrays.asList("text: must not be blank", "entries: must not be empty"),
                messages(result));
    }

    // ===================== numbers =====================

    static class WideNumbersDto {

        @Max(10)
        public long asLong = 1L;

        @Max(10)
        public BigInteger asBigInteger = BigInteger.ONE;

        @Min(1)
        public Byte asByte = (byte) 1;

        @DecimalMin(value = "1.0", inclusive = false)
        public double exclusiveMin = 2.0;

        @Max(100)
        public double notANumber = 1.0;
    }

    @Test
    @DisplayName("a bound reads every numeric width the same way")
    void boundsSpanEveryNumericWidth() {
        final Config c = reporting();
        c.setValue("asLong", 11L);
        c.setValue("asBigInteger", new BigInteger("11"));
        c.setValue("asByte", 0);
        c.setValue("exclusiveMin", 1.0);
        final BindResult<WideNumbersDto> result = c.bind(WideNumbersDto.class).readResult("");

        assertEquals(Arrays.asList(
                "asLong: must be at most 10",
                "asBigInteger: must be at most 10",
                "asByte: must be at least 1",
                "exclusiveMin: must be greater than 1.0"), messages(result));
    }

    @Test
    @DisplayName("a value with no place on the number line satisfies no bound")
    void notANumberSatisfiesNoBound() {
        final WideNumbersDto dto = new WideNumbersDto();
        dto.notANumber = Double.NaN;
        final BindException failure = assertThrows(BindException.class,
                () -> Config.inMemory().withRuleEngine(StandardRules.engine())
                        .bind(WideNumbersDto.class).write("", dto));
        assertTrue(failure.getMessage().contains("rejects the field's OWN DEFAULT value NaN"),
                failure.getMessage());
    }

    // ===================== text =====================

    static class FlaggedTextDto {

        @Pattern(regexp = "[a-z]+", flags = Pattern.Flag.CASE_INSENSITIVE)
        public String anyCase = "abc";

        @Email(regexp = ".*@example\\.com")
        public String corporate = "ops@example.com";

        @Digits(integer = 2, fraction = 1)
        public String writtenNumber = "12.3";
    }

    @Test
    @DisplayName("@Pattern honors its flags, @Email narrows with its own regexp, @Digits reads text")
    void textConstraintsHonorTheirAttributes() {
        final Config c = reporting();
        c.setValue("anyCase", "ABC");            // accepted: the flag is folded into the expression
        c.setValue("corporate", "ops@other.com");
        c.setValue("writtenNumber", "123.4");
        final BindResult<FlaggedTextDto> result = c.bind(FlaggedTextDto.class).readResult("");

        assertEquals(Arrays.asList(
                "corporate: must be a well-formed e-mail address",
                "writtenNumber: must have at most 2 integer digits and 1 decimal places, but has 3 and 1"),
                messages(result));
    }

    // ===================== @Unique =====================

    static class UniqueShapesDto {

        @Unique
        public int[] slots = {1, 2};

        @Unique
        public List<String> maybeNull = new ArrayList<>();
    }

    @Test
    @DisplayName("@Unique compares a primitive array by value, and two nulls are a repeat")
    void uniqueCoversEveryShape() {
        final UniqueShapesDto dto = new UniqueShapesDto();
        dto.slots = new int[]{1, 2, 1};
        dto.maybeNull = new ArrayList<>(Arrays.asList((String) null, null));

        final Config c = reporting();
        final BindResult<UniqueShapesDto> ignored = c.bind(UniqueShapesDto.class).readResult("");
        assertTrue(ignored.issues().isEmpty(), "the defaults are unique");

        c.bind(UniqueShapesDto.class).write("", dto);
        // the write path judges what the POJO holds now, so the duplicates are what it sees
        final BindResult<UniqueShapesDto> reread = c.bind(UniqueShapesDto.class).readResult("");
        assertEquals(Arrays.asList(
                "slots: must not repeat an entry, but '1' appears more than once",
                "maybeNull: must not repeat an entry, but 'null' appears more than once"),
                messages(reread));
    }

    // ===================== @OneOf =====================

    static class UnionOneOfDto {

        @OneOf(value = {"static"}, provider = ExtraValues.class)
        public String world = "static";
    }

    /** Adds one value the annotation does not list, so the union is observable. */
    public static final class ExtraValues implements OneOfSource {

        @Override
        public Collection<String> values() {
            return Arrays.asList("dynamic");
        }
    }

    @Test
    @DisplayName("@OneOf accepts the union of its own list and the provider's")
    void oneOfUnionsBothSources() {
        final Config accepted = reporting();
        accepted.setValue("world", "dynamic");
        assertTrue(accepted.bind(UnionOneOfDto.class).readResult("").issues().isEmpty(),
                "a provider value must be accepted alongside the declared ones");

        final Config rejected = reporting();
        rejected.setValue("world", "end");
        assertEquals(Arrays.asList("world: must be one of static, dynamic, but is 'end'"),
                messages(rejected.bind(UnionOneOfDto.class).readResult("")));
    }

    // ===================== @Explicit =====================

    static class UnwritableSeedDto {

        @Explicit
        @NotBlank
        public String token = "";
    }

    @Test
    @DisplayName("@Explicit weighs only a neighbour the ATTACHED engine claims")
    void explicitJudgesTheSeedWithTheAttachedEngine() {
        // The default engine claims @ConfigRule-marked annotations only, so @NotBlank never fires here and
        // the empty seed it would have refused is one this config writes without complaint.
        final BindResult<UnwritableSeedDto> onlyExplicit =
                Config.inMemory().bind(UnwritableSeedDto.class).readResult("");
        assertEquals(Arrays.asList(
                "token: must be set in the config file; the value in use is the built-in default"),
                messages(onlyExplicit));

        // Attaching jakarta makes the same declaration unwritable, and the failure names both annotations.
        final BindException failure = assertThrows(BindException.class,
                () -> Config.inMemory().withRuleEngine(StandardRules.engine())
                        .bind(UnwritableSeedDto.class).readResult(""));
        assertTrue(failure.getMessage().contains("sits beside @NotBlank"), failure.getMessage());
    }
}
