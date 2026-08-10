package br.com.finalcraft.everyconfig.rule;

import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.section.ConfigSection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The built-in engine: it resolves the handler a rule annotation names, shares one instance of it, and
 *  says what to do when a handler cannot be built. */
class AnnotationRuleEngineTest {

    /** Marked, but left on the inert handler: a declared fact for whoever claims it. */
    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    @ConfigRule
    @interface InertRule {
    }

    /** Its handler needs constructor arguments, which the shared-instance contract cannot provide. */
    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    @ConfigRule(NeedsSetupHandler.class)
    @interface NeedsSetup {
    }

    static final class NeedsSetupHandler implements RuleHandler {

        NeedsSetupHandler(final String setting) {
        }

        @Override
        public void check(final RuleContext context) {
        }
    }

    static class Handled {

        @TestRule("first")
        public int first = 1;

        @TestRule("second")
        public int second = 2;
    }

    static class Inert {

        @InertRule
        public int value = 1;
    }

    static class Unbuildable {

        @NeedsSetup
        public int value = 1;
    }

    private final Config config = Config.inMemory();

    @BeforeEach
    void clearRecorder() {
        TestRuleHandler.CHECKED.clear();
    }

    @Test
    void applyDelegatesToTheDeclaredHandlerAndSharesOneInstance() {
        final Handled entity = new Handled();
        for (final RuleSite site : RuleModel.of(Handled.class)) {
            AnnotationRuleEngine.INSTANCE.apply(contextFor(site, entity));
        }
        assertEquals(Arrays.asList("first", "second"), TestRuleHandler.CHECKED);
        assertEquals(1, TestRuleHandler.instances);
    }

    @Test
    void describeDelegatesToTheDeclaredHandler() {
        final RuleSite site = RuleModel.of(Handled.class).get(0);
        assertEquals(Arrays.asList("rule: first"), AnnotationRuleEngine.INSTANCE.describe(site));
    }

    @Test
    void anInertRuleIsLeftAlone() {
        final List<RuleSite> sites = RuleModel.of(Inert.class);
        assertEquals(1, sites.size());
        AnnotationRuleEngine.INSTANCE.apply(contextFor(sites.get(0), new Inert()));
        assertTrue(TestRuleHandler.CHECKED.isEmpty());
        assertTrue(AnnotationRuleEngine.INSTANCE.describe(sites.get(0)).isEmpty());
    }

    @Test
    void aHandlerThatCannotBeBuiltSaysHowToFixIt() {
        final RuleSite site = RuleModel.of(Unbuildable.class).get(0);
        final BindException failure = assertThrows(BindException.class,
                () -> AnnotationRuleEngine.INSTANCE.describe(site));
        assertTrue(failure.getMessage().contains("no-argument constructor"), failure.getMessage());
        assertTrue(failure.getMessage().contains(NeedsSetupHandler.class.getName()), failure.getMessage());
    }

    private RuleContext contextFor(final RuleSite site, final Object owner) {
        return new RuleContext(site, RulePhase.VALIDATE, null, owner, ValueSource.FILE,
                new ConfigSection(config, site.path()), violation -> {
        }, AnnotationRuleEngine.INSTANCE, RulePolicy.defaults(), false);
    }
}
