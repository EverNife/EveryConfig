package br.com.finalcraft.everyconfig.rule;

import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.section.ConfigSection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a handler sees: the severity the policy would resolve for it, and the correction it may apply. */
class RuleContextTest {

    static class Corrigible {

        @TestRule("bounded")
        public int value = 150;
    }

    private final Config config = Config.inMemory();

    @Test
    void severityForFollowsTheOriginThenThePolicyThenTheCoercion() {
        final RuleContext lenient = contextFor(RulePolicy.defaults(), false);
        assertEquals(RulePolicy.Severity.THROW, lenient.severityFor(ValueSource.DEFAULT));
        assertEquals(RulePolicy.Severity.REPORT, lenient.severityFor(ValueSource.FILE));

        final RuleContext strict = contextFor(RulePolicy.defaults(), true);
        assertEquals(RulePolicy.Severity.THROW, strict.severityFor(ValueSource.FILE));

        final RuleContext declared = contextFor(
                RulePolicy.defaults().withSeverity(RulePolicy.Severity.LOG), true);
        assertEquals(RulePolicy.Severity.LOG, declared.severityFor(ValueSource.FILE));

        final RuleContext relaxed = contextFor(
                RulePolicy.defaults().withDefaultViolations(RulePolicy.Severity.REPORT), false);
        assertEquals(RulePolicy.Severity.REPORT, relaxed.severityFor(ValueSource.DEFAULT));
    }

    @Test
    void correctionIsOffUntilThePolicyEnablesIt() {
        final Corrigible entity = new Corrigible();
        final RuleSite site = RuleModel.of(Corrigible.class).get(0);

        assertFalse(context(site, entity, RulePolicy.defaults()).correct(100));
        assertEquals(150, entity.value);

        assertTrue(context(site, entity, RulePolicy.defaults().withCorrections(true)).correct(100));
        assertEquals(100, entity.value);
    }

    @Test
    void aSiteWithNoFieldCannotBeCorrected() {
        final RuleSite typeSite = RuleModel.of(RuleFixtures.Hairy.class).get(4);
        assertEquals(RuleSite.Kind.TYPE, typeSite.kind());
        assertFalse(context(typeSite, new RuleFixtures.Hairy(), RulePolicy.defaults().withCorrections(true))
                .correct("anything"));
    }

    private RuleContext contextFor(final RulePolicy policy, final boolean strictCoercion) {
        final RuleSite site = RuleModel.of(Corrigible.class).get(0);
        return new RuleContext(site, RulePhase.VALIDATE, 150, new Corrigible(), ValueSource.FILE,
                new ConfigSection(config, site.path()), violation -> {
        }, AnnotationRuleEngine.INSTANCE, policy, strictCoercion);
    }

    private RuleContext context(final RuleSite site, final Object owner, final RulePolicy policy) {
        return new RuleContext(site, RulePhase.VALIDATE, null, owner, ValueSource.FILE,
                new ConfigSection(config, site.path()), violation -> {
        }, AnnotationRuleEngine.INSTANCE, policy, false);
    }
}
