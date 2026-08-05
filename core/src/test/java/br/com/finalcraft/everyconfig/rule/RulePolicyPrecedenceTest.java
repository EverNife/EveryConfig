package br.com.finalcraft.everyconfig.rule;

import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.binding.BindOptions;
import br.com.finalcraft.everyconfig.binding.BindResult;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.codec.jackson.YamlCodec;
import br.com.finalcraft.everyconfig.config.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a violation costs, and who gets to say so. The order is fixed — the engine's stamp, then the policy's
 * answer for the value's origin, then the strictness the bind was already given — and the two failure texts
 * have to teach the way out of the situation they describe.
 */
class RulePolicyPrecedenceTest {

    /** Bounded, and broken by the file. */
    static class FileBreaker {

        @TestMax(100)
        public int limit = 5;
    }

    /** Bounded, and broken by its own initializer: a defect no file can fix. */
    static class DefaultBreaker {

        @TestMax(100)
        public int dropChance = 150;
    }

    /** The engine knows this rule well enough to price it itself. */
    static class Stamped {

        @TestMax(value = 100, stamped = true, severity = RulePolicy.Severity.REPORT)
        public int limit = 5;
    }

    /** Priced LOG, on a type of its own so the once-per-site memory is not shared with another test. */
    static class Logged {

        @TestMax(value = 100, stamped = true, severity = RulePolicy.Severity.LOG)
        public int limit = 5;
    }

    private final YamlCodec yaml = new YamlCodec();

    /** A config whose {@code limit} the file already broke. */
    private Config broken() {
        final Config config = Config.inMemory(yaml);
        config.setValue("limit", 150);
        return config;
    }

    @Test
    void anEngineStampOutranksTheDeclaredPolicy() {
        final Config config = broken().withRulePolicy(
                RulePolicy.defaults().withSeverity(RulePolicy.Severity.THROW));

        final BindResult<Stamped> stamped = config.loadAsResult(Stamped.class, yaml);
        assertEquals(1, stamped.issues().size(), "the engine said REPORT, so the policy's THROW must yield");

        // ...and without the stamp the same policy fails the bind, which is what makes the comparison mean something
        assertThrows(BindException.class, () -> config.loadAs(FileBreaker.class, yaml));
    }

    @Test
    void aFileViolationFollowsTheBindsCoercionWhenThePolicyDeclaresNothing() {
        final Config config = broken();

        final BindResult<FileBreaker> lenient = config.bind(FileBreaker.class, yaml).readResult("");
        assertEquals(1, lenient.issues().size(), "lenient coercion reports a file violation");
        assertEquals(LoadIssue.Kind.RULE, lenient.issues().get(0).kind());

        final BindOptions strict = BindOptions.defaults().withCoercion(BindOptions.Coercion.STRICT);
        final BindException thrown = assertThrows(BindException.class,
                () -> config.bind(FileBreaker.class, yaml, strict).read(""));
        assertEquals("Rule @TestMax(100) at 'limit' rejects the file value '150'. Fix the value in the file, "
                + "or relax the rule on FileBreaker.limit.", thrown.getMessage());
    }

    @Test
    void aDefaultBreakingItsOwnRuleThrowsOutOfTheBoxAndSaysWhyNoFileCanHelp() {
        final Config config = Config.inMemory(yaml); // nothing on disk: the value is the entity's own
        final BindException thrown = assertThrows(BindException.class,
                () -> config.loadAs(DefaultBreaker.class, yaml));

        assertEquals("@TestMax(100) on DefaultBreaker.dropChance ('dropChance') rejects the field's OWN "
                + "DEFAULT value 150. This is a code defect, not user data: no config file can fix it, and "
                + "every run reproduces it. Change the field's initializer or relax the rule.",
                thrown.getMessage());
    }

    @Test
    void withDefaultViolationsDowngradesTheFactoryThrow() {
        final Config config = Config.inMemory(yaml).withRulePolicy(
                RulePolicy.defaults().withDefaultViolations(RulePolicy.Severity.REPORT));

        final BindResult<DefaultBreaker> result = config.loadAsResult(DefaultBreaker.class, yaml);
        assertEquals(1, result.issues().size());
        assertEquals(150, result.value().dropChance, "reporting keeps the value; only THROW stops the bind");
    }

    @Test
    void logSeverityWarnsOncePerSiteHoweverOftenTheConfigIsLoaded() {
        final Config config = broken();
        final LogRecorder recorder = LogRecorder.attachedTo(RuleBindDriver.class);
        try {
            config.loadAs(Logged.class, yaml);
            config.loadAs(Logged.class, yaml);
            config.loadAs(Logged.class, yaml);
        } finally {
            recorder.detach();
        }

        assertEquals(1, recorder.records.size(), "a LOG violation warns once per site, got "
                + recorder.records);
        assertTrue(recorder.records.get(0).contains("must be at most 100"), recorder.records.get(0));
    }
}
