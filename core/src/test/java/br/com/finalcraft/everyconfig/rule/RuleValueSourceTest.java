package br.com.finalcraft.everyconfig.rule;

import br.com.finalcraft.everyconfig.annotation.EveryConfigCompactCreator;
import br.com.finalcraft.everyconfig.annotation.EveryConfigCompactValue;
import br.com.finalcraft.everyconfig.binding.BindResult;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.codec.jackson.YamlCodec;
import br.com.finalcraft.everyconfig.config.Config;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a judged value is deemed to come from, and the sites a bind has nothing to judge at. The distinction
 * decides between reporting user data and failing on a code defect, so it has to survive the cases where the
 * value the entity ends up holding is not the one the file wrote.
 */
class RuleValueSourceTest {

    /** Its own initializer is already out of range, so whatever the file does the rule fires. */
    static class Bounded {

        @TestMax(100)
        public int limit = 150;
    }

    static class Inner {

        @TestMax(100)
        public int limit = 150;
    }

    /** The nested owner starts null and only exists when the file gives it something. */
    static class Outer {

        public Inner inner;
    }

    /** Rules declared on a type that persists as one compact string, which leaves them nowhere to be judged. */
    public static class CompactBounded {

        @TestMax(100)
        public int limit;

        public CompactBounded() {
        }

        public CompactBounded(final int limit) {
            this.limit = limit;
        }

        @EveryConfigCompactValue
        public String toElementString() {
            return String.valueOf(limit);
        }

        @EveryConfigCompactCreator
        public static CompactBounded fromElementString(final String text) {
            return new CompactBounded(Integer.parseInt(text.trim()));
        }
    }

    private final YamlCodec yaml = new YamlCodec();

    @Test
    void aValueTheLenientRetryThrewAwayIsStillFileSourced() {
        final Config config = Config.inMemory(yaml);
        config.setValue("limit", "boom"); // present in the file, and unbindable

        final BindResult<Bounded> result = config.loadAsResult(Bounded.class, yaml);

        // had the discarded value made the site default-sourced, the factory policy would have thrown here
        assertEquals(2, result.issues().size(), "expected one coercion + one rule issue, got "
                + result.issues());
        final LoadIssue rule = ruleIssue(result.issues());
        assertEquals(ValueSource.FILE, rule.violation().source());
        assertEquals(150, result.value().limit, "the field kept its own default, as the coercion decided");
    }

    @Test
    void aNullIntermediateOwnerSkipsEverySiteBelowIt() {
        final Config empty = Config.inMemory(yaml);
        final BindResult<Outer> absent = empty.loadAsResult(Outer.class, yaml);

        assertNull(absent.value().inner);
        assertTrue(absent.issues().isEmpty(), "there is no value to judge under a null owner, got "
                + absent.issues());

        // and once the owner exists, the very same site does fire
        final Config present = Config.inMemory(yaml);
        present.setValue("inner.limit", 150);
        final BindResult<Outer> bound = present.loadAsResult(Outer.class, yaml);
        assertEquals("inner.limit", ruleIssue(bound.issues()).key());
    }

    @Test
    void aCompactElementTypeCarryingRulesWarnsOncePerType() {
        final LogRecorder recorder = LogRecorder.attachedTo(RuleBindDriver.class);
        try {
            final Config config = Config.inMemory(yaml);
            config.setValue("spots", Arrays.asList(new CompactBounded(150), new CompactBounded(200)));
            config.getList("spots", CompactBounded.class);
            config.getList("spots", CompactBounded.class);
        } finally {
            recorder.detach();
        }

        assertEquals(1, recorder.records.size(), "the compact warning is once per type, got "
                + recorder.records);
        assertTrue(recorder.records.get(0).contains(CompactBounded.class.getName()),
                recorder.records.get(0));
    }

    private static LoadIssue ruleIssue(final List<LoadIssue> issues) {
        for (final LoadIssue issue : issues) {
            if (issue.kind() == LoadIssue.Kind.RULE) {
                return issue;
            }
        }
        throw new AssertionError("expected a rule issue in " + issues);
    }
}
