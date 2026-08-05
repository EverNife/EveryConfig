package br.com.finalcraft.everyconfig.ruleset.contract;

import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.binding.BindResult;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.rule.RulePolicy;
import br.com.finalcraft.everyconfig.ruleset.StandardRules;
import br.com.finalcraft.everyconfig.ruleset.data.RulesetDtos;
import br.com.finalcraft.everyconfig.testkit.CodecMatrixTest;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The codec-agnostic contract of the standard rule vocabulary: every {@code @Test} here binds a real file
 * through {@link StandardRules}, so a constraint is proven on the format it will actually be written in —
 * a bound on a {@code double} has to survive TOML's float parsing exactly as it survives YAML's.
 *
 * <p>One abstract body, one thin subclass per codec, on the same {@link CodecMatrixTest} harness the core
 * contract runs on. Behavior that has nothing to do with the format — a constraint declared on a type it
 * cannot judge, the exact wording of a message — is asserted once here rather than four times.
 */
public abstract class AbstractRulesetTest extends CodecMatrixTest {

    @Override
    protected String residualsGroup() {
        return "ruleset";
    }

    // ===================== harness =====================

    /**
     * Write {@code path, value} pairs into the file, then reopen it with the standard vocabulary attached —
     * so everything the bind sees at those paths is FILE data, and everything else is the entity's default.
     */
    protected Config seeded(final Object... pathsAndValues) {
        final Config seed = open();
        for (int i = 0; i < pathsAndValues.length; i += 2) {
            seed.setValue((String) pathsAndValues[i], pathsAndValues[i + 1]);
        }
        seed.save();
        return standard();
    }

    /** A config over the same file with the standard vocabulary attached. */
    protected Config standard() {
        return open().withRuleEngine(StandardRules.engine());
    }

    protected static List<LoadIssue> rules(final BindResult<?> result) {
        final List<LoadIssue> found = new ArrayList<>();
        for (final LoadIssue issue : result.issues()) {
            if (issue.kind() == LoadIssue.Kind.RULE) {
                found.add(issue);
            }
        }
        return found;
    }

    /** The single rule issue at {@code path}, failing with the whole list when it is not there. */
    protected static LoadIssue ruleAt(final BindResult<?> result, final String path) {
        LoadIssue found = null;
        for (final LoadIssue issue : rules(result)) {
            if (path.equals(issue.key())) {
                assertNull(found, "expected exactly one rule issue at '" + path + "' in " + result.issues());
                found = issue;
            }
        }
        assertNotNull(found, "expected a rule issue at '" + path + "', got " + result.issues());
        return found;
    }

    protected static List<String> pathsOf(final List<LoadIssue> issues) {
        final List<String> paths = new ArrayList<>();
        for (final LoadIssue issue : issues) {
            paths.add(issue.key());
        }
        Collections.sort(paths);
        return paths;
    }

    // ===================== numeric =====================

    @Test
    @DisplayName("[jakarta] a bound rejects the value the FILE supplied, on an int and on a double alike")
    void numericBoundsRejectFileValues() {
        final Config c = seeded("port", 70000, "dropChance", 150.0);
        final BindResult<RulesetDtos.NumericDto> result =
                c.bind(RulesetDtos.NumericDto.class, codec).readResult("");

        assertEquals(Arrays.asList("dropChance", "port"), pathsOf(rules(result)));
        assertEquals("must be at most 65535", ruleAt(result, "port").message());
        assertEquals("must be at most 100", ruleAt(result, "dropChance").message());
    }

    @Test
    @DisplayName("[jakarta] a value inside the bounds reports nothing, decimals included")
    void numericBoundsAcceptWhatFits() {
        final Config c = seeded("port", 8080, "dropChance", 99.5, "ratio", "0.5", "belowTen", 9.999);
        final BindResult<RulesetDtos.NumericDto> result =
                c.bind(RulesetDtos.NumericDto.class, codec).readResult("");

        assertTrue(result.issues().isEmpty(), "expected a clean bind, got " + result.issues());
        assertEquals(99.5, result.value().dropChance, 0.0);
    }

    @Test
    @DisplayName("[jakarta] an absent wrapper stays null and every bound lets it through")
    void absentValuesPassEveryBound() {
        final BindResult<RulesetDtos.NumericDto> result =
                standard().bind(RulesetDtos.NumericDto.class, codec).readResult("");

        assertTrue(result.issues().isEmpty(), "null must satisfy everything but presence, got "
                + result.issues());
        assertNull(result.value().optionalMax, "the wrapper had no file value to take");
    }

    @Test
    @DisplayName("[jakarta] @DecimalMin/@DecimalMax compare exactly, and exclusive means exclusive")
    void decimalBoundsCompareExactly() {
        final Config c = seeded("ratio", "0.4999", "belowTen", 10.0);
        final BindResult<RulesetDtos.NumericDto> result =
                c.bind(RulesetDtos.NumericDto.class, codec).readResult("");

        assertEquals(Arrays.asList("belowTen", "ratio"), pathsOf(rules(result)));
        assertEquals("must be less than 10", ruleAt(result, "belowTen").message());
    }

    @Test
    @DisplayName("[jakarta] the four sign constraints each reject the side they exclude")
    void signConstraintsRejectTheWrongSide() {
        final Config c = seeded("positive", 0, "positiveOrZero", -1, "negative", 0, "negativeOrZero", 1);
        final BindResult<RulesetDtos.NumericDto> result =
                c.bind(RulesetDtos.NumericDto.class, codec).readResult("");

        assertEquals(Arrays.asList("negative", "negativeOrZero", "positive", "positiveOrZero"),
                pathsOf(rules(result)));
        assertEquals("must be greater than 0", ruleAt(result, "positive").message());
        assertEquals("must be 0 or less", ruleAt(result, "negativeOrZero").message());
    }

    @Test
    @DisplayName("[jakarta] @Digits counts the digits on each side of the point")
    void digitsCountBothSides() {
        final Config c = seeded("price", "12.345");
        final BindResult<RulesetDtos.NumericDto> result =
                c.bind(RulesetDtos.NumericDto.class, codec).readResult("");

        assertEquals("must have at most 3 integer digits and 2 decimal places, but has 2 and 3",
                ruleAt(result, "price").message());
    }

    // ===================== text, presence and size =====================

    @Test
    @DisplayName("[jakarta] blank text, a bad size, a bad pattern and a bad address each report")
    void textConstraintsRejectFileValues() {
        final Config c = seeded("name", "   ", "code", "a", "slug", "Bad Slug", "contact", "nope");
        final BindResult<RulesetDtos.TextDto> result =
                c.bind(RulesetDtos.TextDto.class, codec).readResult("");

        assertEquals(Arrays.asList("code", "contact", "name", "slug"), pathsOf(rules(result)));
        assertEquals("must not be blank", ruleAt(result, "name").message());
        assertEquals("size must be between 2 and 5, but is 1", ruleAt(result, "code").message());
        assertEquals("must match '[a-z-]+'", ruleAt(result, "slug").message());
        assertEquals("must be a well-formed e-mail address", ruleAt(result, "contact").message());
    }

    @Test
    @DisplayName("[jakarta] @Size and @NotEmpty count a list, a map and an array the same way")
    void sizeCountsEveryTargetShape() {
        final Config c = seeded(
                "worlds", new ArrayList<String>(),
                "aliases.a", "1", "aliases.b", "2", "aliases.c", "3",
                "slots", new ArrayList<Integer>());
        final BindResult<RulesetDtos.TextDto> result =
                c.bind(RulesetDtos.TextDto.class, codec).readResult("");

        assertEquals(Arrays.asList("aliases", "slots", "worlds"), pathsOf(rules(result)));
        assertEquals("must not be empty", ruleAt(result, "worlds").message());
        assertEquals("size must be at most 2, but is 3", ruleAt(result, "aliases").message());
    }

    @Test
    @DisplayName("[jakarta] an explicit null in the file fails presence and passes everything else")
    void explicitNullFailsOnlyPresence() {
        assumeNullSupported();
        // an explicit null node, not a Java null - setValue(path, null) REMOVES the path
        final Config c = seeded("required", NullNode.getInstance());
        final BindResult<RulesetDtos.TextDto> result =
                c.bind(RulesetDtos.TextDto.class, codec).readResult("");

        assertEquals(Collections.singletonList("required"), pathsOf(rules(result)));
        assertEquals("must not be null", ruleAt(result, "required").message());
    }

    // ===================== temporal =====================

    @Test
    @DisplayName("[jakarta] each temporal type is judged against its own now()")
    void temporalConstraintsJudgeAgainstNow() {
        final Config c = seeded(
                "started", LocalDate.now().plusDays(1).toString(),
                "expires", LocalDate.now().minusDays(1).toString(),
                "seen", Instant.now().plusSeconds(3600).toString(),
                "due", LocalDateTime.now().minusDays(1).toString());
        final BindResult<RulesetDtos.TemporalDto> result =
                c.bind(RulesetDtos.TemporalDto.class, codec).readResult("");

        assertEquals(Arrays.asList("due", "expires", "seen", "started"), pathsOf(rules(result)));
        assertEquals("must be in the past", ruleAt(result, "started").message());
        assertEquals("must be now or in the future", ruleAt(result, "due").message());
    }

    @Test
    @DisplayName("[jakarta] temporal values on the right side of now report nothing")
    void temporalConstraintsAcceptTheRightSide() {
        final BindResult<RulesetDtos.TemporalDto> result =
                standard().bind(RulesetDtos.TemporalDto.class, codec).readResult("");

        assertTrue(result.issues().isEmpty(), "expected a clean bind, got " + result.issues());
    }

    // ===================== the asserting getter =====================

    @Test
    @DisplayName("[jakarta] @AssertTrue on a getter is the cross-field invariant, message and all")
    void assertingGetterIsTheCrossFieldInvariant() {
        final Config c = seeded("min", 10, "max", 1);
        final BindResult<RulesetDtos.RangeDto> result =
                c.bind(RulesetDtos.RangeDto.class, codec).readResult("");

        assertEquals(2, rules(result).size(), "expected both assertions to fire, got " + result.issues());
        assertTrue(messages(result).contains("'min' must not be greater than 'max'"),
                "the author's own message must survive verbatim, got " + messages(result));
        assertTrue(messages(result).contains("must be false"), messages(result).toString());
    }

    @Test
    @DisplayName("[jakarta] an asserting getter paired with @JsonIgnore never becomes a key in the file")
    void assertingGetterIsNotAKey() throws IOException {
        final Config c = standard();
        c.bind(RulesetDtos.RangeDto.class, codec).write("", new RulesetDtos.RangeDto());
        c.save();

        final String text = readText();
        assertFalse(text.contains("rangeValid"), "the asserting getter leaked into the file:\n" + text);
        assertFalse(text.contains("inverted"), "the asserting getter leaked into the file:\n" + text);
    }

    // ===================== EveryConfig's own vocabulary =====================

    @Test
    @DisplayName("[@Explicit] fires on the first run without throwing, and is satisfied once the file has it")
    void explicitCatchesTheFirstRun() {
        final BindResult<RulesetDtos.VocabularyDto> first =
                standard().bind(RulesetDtos.VocabularyDto.class, codec).readResult("");
        assertEquals("must be set in the config file; the value in use is the built-in default",
                ruleAt(first, "token").message());

        final Config write = standard();
        write.bind(RulesetDtos.VocabularyDto.class, codec).write("", new RulesetDtos.VocabularyDto());
        write.save();

        final BindResult<RulesetDtos.VocabularyDto> second =
                standard().bind(RulesetDtos.VocabularyDto.class, codec).readResult("");
        assertTrue(rules(second).isEmpty(), "the seeded key satisfies @Explicit, got " + second.issues());
    }

    @Test
    @DisplayName("[@Explicit] a strict bind refuses to load without an explicit value")
    void explicitThrowsUnderAStrictBind() {
        final Config c = standard().withRulePolicy(RulePolicy.defaults()
                .withSeverity(RulePolicy.Severity.THROW));
        final BindException failure = assertThrows(BindException.class,
                () -> c.bind(RulesetDtos.VocabularyDto.class, codec).read(""));
        assertEquals("Rule @Explicit at 'token' rejects the value 'changeme' in use: must be set in the "
                + "config file; the value in use is the built-in default. Set it in the file, or relax the "
                + "rule on VocabularyDto.token.", failure.getMessage());
    }

    @Test
    @DisplayName("[@OneOf] ignores case when told to, and reports one violation per offending element")
    void oneOfMatchesPerElement() {
        final Config c = seeded("dbType", "mongo", "stages", Arrays.asList("alpha", "gamma", "delta"),
                "token", "real-token");
        final BindResult<RulesetDtos.VocabularyDto> result =
                c.bind(RulesetDtos.VocabularyDto.class, codec).readResult("");

        assertEquals(2, rules(result).size(), "one violation per offending element, got " + result.issues());
        assertEquals("must be one of alpha, beta, but is 'gamma'", rules(result).get(0).message());
        assertEquals("gamma", rules(result).get(0).rawValue());
        assertEquals("delta", rules(result).get(1).rawValue());
    }

    @Test
    @DisplayName("[@OneOf] a provider is asked again on every bind, so a set that grows is honored")
    void oneOfProviderIsReadEveryTime() {
        RulesetDtos.LoadedWorlds.CURRENT.set(new ArrayList<String>());
        final Config c = seeded("world", "nether");

        assertEquals("must be one of static, but is 'nether'",
                ruleAt(c.bind(RulesetDtos.DynamicOneOfDto.class, codec).readResult(""), "world").message());

        RulesetDtos.LoadedWorlds.CURRENT.set(new ArrayList<>(Arrays.asList("nether")));
        assertTrue(rules(standard().bind(RulesetDtos.DynamicOneOfDto.class, codec).readResult("")).isEmpty(),
                "the provider's new value must be accepted without reopening anything");
    }

    @Test
    @DisplayName("[@Unique] reports the first repeated entry, naming it")
    void uniqueReportsTheFirstRepeat() {
        final Config c = seeded("worlds", Arrays.asList("world", "nether", "world"), "token", "real-token");
        final BindResult<RulesetDtos.VocabularyDto> result =
                c.bind(RulesetDtos.VocabularyDto.class, codec).readResult("");

        final LoadIssue repeat = ruleAt(result, "worlds");
        assertEquals("must not repeat an entry, but 'world' appears more than once", repeat.message());
        assertEquals("world", repeat.rawValue());
    }

    // ===================== the three vocabularies together =====================

    @Test
    @DisplayName("[standard] jakarta, EveryConfig's own and a consumer's rule all fire on ONE field")
    void everyVocabularyFiresOnTheSameField() {
        final Config c = seeded("dbType", "no sql");
        final BindResult<RulesetDtos.MixedVocabularyDto> result =
                c.bind(RulesetDtos.MixedVocabularyDto.class, codec).readResult("");

        assertEquals(3, rules(result).size(), "expected @Size, @OneOf and @NoSpaces, got " + result.issues());
        assertTrue(messages(result).contains("size must be at most 2, but is 6"), messages(result).toString());
        assertTrue(messages(result).contains("must be one of MONGO, SQL, but is 'no sql'"),
                messages(result).toString());
        assertTrue(messages(result).contains("must not contain a space"), messages(result).toString());
    }

    @Test
    @DisplayName("[standard] a config with no candidate annotation round-trips byte-identically")
    void inertiaForAConfigWithoutRules() throws IOException {
        final Config c = standard();
        c.bind(RulesetDtos.PlainDto.class, codec).write("", new RulesetDtos.PlainDto());
        c.save();
        final String first = readText();

        final Config reopened = standard();
        final BindResult<RulesetDtos.PlainDto> result =
                reopened.bind(RulesetDtos.PlainDto.class, codec).readResult("");
        assertTrue(result.issues().isEmpty(), "a rule-free config must report nothing, got " + result.issues());
        assertFalse(reopened.hasRuleFixes());

        reopened.bind(RulesetDtos.PlainDto.class, codec).write("", result.value());
        reopened.save();
        assertEquals(first, readText(), "the standard engine must not change a single byte");
    }

    // ===================== paths =====================

    @Test
    @DisplayName("[paths] a rule is reported where its value LANDS - @Section and nesting applied")
    void rulesAreReportedAtTheFilePath() {
        final Config c = seeded("server.network.port", 70000, "pool.size", 0);
        final BindResult<RulesetDtos.NestedDto> result =
                c.bind(RulesetDtos.NestedDto.class, codec).readResult("");

        assertEquals(Arrays.asList("pool.size", "server.network.port"), pathsOf(rules(result)));
    }

    // ===================== declaration defects =====================

    @Test
    @DisplayName("[declaration] a constraint on a type it cannot judge fails and names the way out")
    void incompatibleTargetFailsWithATeachingMessage() {
        final BindException failure = assertThrows(BindException.class,
                () -> standard().bind(RulesetDtos.BadTargetDto.class, codec).read(""));
        assertEquals("@Size on BadTargetDto.count ('count') cannot be applied to int. It accepts text, a "
                + "collection, a map or an array - move the constraint to a member of one of those types, "
                + "or remove it.", failure.getMessage());
    }

    // ===================== review =====================

    @Test
    @DisplayName("[review] the entity accepts a magic word its own annotation could never list")
    void reviewAcceptsTheMagicWord() {
        final Config c = seeded("dbType", "$auto");
        final BindResult<RulesetDtos.MagicWordDto> result =
                c.bind(RulesetDtos.MagicWordDto.class, codec).readResult("");

        assertTrue(result.issues().isEmpty(), "the review accepted it, so nothing is reported: "
                + result.issues());
        assertEquals("$auto", result.value().dbType, "an accepted value is kept as written");
    }

    @Test
    @DisplayName("[review] a check that reads ANOTHER config fails the load, and passes once that file agrees")
    void reviewReachesAnotherConfig() {
        final Config storage = open(residualDir.resolve("storage." + fileExtension()));
        storage.setValue("databases.main.enabled", false);
        storage.save();
        RulesetDtos.DatabaseRefDto.STORAGE.set(storage);
        try {
            final Config c = seeded("databaseId", "main");
            final BindException failure = assertThrows(BindException.class,
                    () -> c.bind(RulesetDtos.DatabaseRefDto.class, codec).read(""));
            assertTrue(failure.getMessage().contains("storage declares 'main' as disabled"),
                    failure.getMessage());

            storage.setValue("databases.main.enabled", true);
            assertTrue(standard().bind(RulesetDtos.DatabaseRefDto.class, codec).readResult("")
                    .issues().isEmpty(), "an enabled database has nothing to report");
        } finally {
            RulesetDtos.DatabaseRefDto.STORAGE.set(null);
        }
    }

    @Test
    @DisplayName("[review] a correction reaches the tree and a save persists it")
    void reviewCorrectionPersists() {
        final Config c = seeded("limit", 250);
        final BindResult<RulesetDtos.ClampingReviewDto> result =
                c.bind(RulesetDtos.ClampingReviewDto.class, codec).readResult("");

        assertEquals(100, result.value().limit, "the review clamped the entity");
        assertEquals(100, c.getInt("limit"), "and the canonical tree, or the next save would undo it");
        assertTrue(c.hasRuleFixes());
        c.save();
        assertEquals(100, open().getInt("limit"), "the file carries what the load fixed");
    }

    // ===================== describe() as a comment =====================

    @Test
    @DisplayName("[comments] withRuleComments composes the rule text under the field's own comment")
    void ruleCommentsComposeUnderTheFieldComment() throws IOException {
        assumeComments();
        final Config c = standard().withRuleComments(true);
        c.bind(RulesetDtos.DocumentedDto.class, codec).write("", new RulesetDtos.DocumentedDto());
        c.save();

        assertEquals("Port the service binds to.\nAt most 65535.\nAt least 1.", c.getComment("port"));
        assertEquals("No duplicate entries.", c.getComment("worlds"));

        final String once = readText();
        final Config again = standard().withRuleComments(true);
        again.bind(RulesetDtos.DocumentedDto.class, codec).write("", new RulesetDtos.DocumentedDto());
        again.save();
        assertEquals(once, readText(), "the composed comment must not grow on a second save");
    }

    @Test
    @DisplayName("[comments] without the flag the file carries the hand-written comment and nothing else")
    void ruleCommentsAreOffByDefault() throws IOException {
        assumeComments();
        final Config c = standard();
        c.bind(RulesetDtos.DocumentedDto.class, codec).write("", new RulesetDtos.DocumentedDto());
        c.save();

        assertEquals("Port the service binds to.", c.getComment("port"));
        assertFalse(readText().contains("At most 65535."), "rule text must not appear unasked:\n" + readText());
    }

    private static List<String> messages(final BindResult<?> result) {
        final List<String> texts = new ArrayList<>();
        for (final LoadIssue issue : rules(result)) {
            texts.add(issue.message());
        }
        return texts;
    }
}
