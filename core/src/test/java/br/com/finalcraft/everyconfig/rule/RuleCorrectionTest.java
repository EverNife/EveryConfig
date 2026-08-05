package br.com.finalcraft.everyconfig.rule;

import br.com.finalcraft.everyconfig.binding.BindResult;
import br.com.finalcraft.everyconfig.codec.jackson.YamlCodec;
import br.com.finalcraft.everyconfig.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a correction reaches. The tree is canonical, so fixing a file value only on the entity would be
 * undone by the next read and re-written by the next save; fixing a value the file never carried is another
 * matter, since materializing an absent key is the seeding's job, not a rule's.
 */
class RuleCorrectionTest {

    /** Clamps a file value back into range instead of only complaining about it. */
    static class Clamped {

        @TestMax(value = 100, correctTo = 100)
        public int limit = 5;
    }

    /** Out of range by its own initializer, so a correction has no file value to reach. */
    static class ClampedDefault {

        @TestMax(value = 100, correctTo = 100)
        public int limit = 250;
    }

    private final YamlCodec yaml = new YamlCodec();

    @Test
    void aFileValueIsCorrectedOnTheEntityAndInTheTreeAndSurvivesASave(@TempDir final Path dir) {
        final Path file = dir.resolve("bounded.yml");
        final Config seed = Config.open(file, yaml);
        seed.setValue("limit", 150);
        seed.save();

        final Config config = Config.open(file, yaml)
                .withRulePolicy(RulePolicy.defaults().withCorrections(true));
        final BindResult<Clamped> result = config.loadAsResult(Clamped.class, yaml);

        assertEquals(100, result.value().limit, "the entity carries the corrected value");
        assertEquals(100, config.getInt("limit"), "and so does the canonical tree");
        assertTrue(config.hasRuleFixes());
        assertEquals(1, result.issues().size(), "correcting does not silence the finding");
        assertEquals(150, result.issues().get(0).rawValue(), "the issue still names what the file held");

        assertEquals(150, Config.open(file, yaml).getInt("limit"), "reading never writes the file");
        config.save();
        assertEquals(100, Config.open(file, yaml).getInt("limit"), "an explicit save persists the fix");

        config.clearRuleFixes();
        assertFalse(config.hasRuleFixes());
    }

    @Test
    void aDefaultValueIsCorrectedOnTheEntityOnly() {
        final Config config = Config.inMemory(yaml).withRulePolicy(RulePolicy.defaults()
                .withCorrections(true)
                .withDefaultViolations(RulePolicy.Severity.REPORT));

        final BindResult<ClampedDefault> result = config.loadAsResult(ClampedDefault.class, yaml);

        assertEquals(100, result.value().limit);
        assertNull(config.getNode("limit"), "an absent key stays absent; materializing it is seeding's job");
        assertFalse(config.hasRuleFixes());
        assertEquals(1, result.issues().size());
    }

    @Test
    void correctionsAreOffUntilThePolicyEnablesThem() {
        final boolean[] applied = {true};
        final Config config = Config.inMemory(yaml);
        config.setValue("limit", 150);
        config.withRuleEngine(context -> {
            applied[0] = context.correct(100);
            context.report().violation(RuleViolation.of(context.site(), context.source(), context.value(),
                    "everyconfig.test.max", Collections.<Object>emptyList(), "must be at most 100"));
        });

        final BindResult<Clamped> result = config.loadAsResult(Clamped.class, yaml);

        assertFalse(applied[0], "correct() reports that the policy refused it");
        assertEquals(150, result.value().limit, "the entity was not touched");
        assertEquals(150, config.getInt("limit"), "nor was the tree");
        assertFalse(config.hasRuleFixes());
        assertEquals(1, result.issues().size(), "the finding is reported either way");
    }
}
