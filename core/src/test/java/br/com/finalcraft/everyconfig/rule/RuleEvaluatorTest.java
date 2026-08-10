package br.com.finalcraft.everyconfig.rule;

import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.section.ConfigSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rules judged outside a bind: the caller says where the value lives and where it came from, and gets back
 *  what it costs instead of a failed load. */
class RuleEvaluatorTest {

    static class Bounded {

        @TestMax(10)
        public int rows = 3;
    }

    static class Clamped {

        @TestMax(value = 10, correctTo = 10)
        public int rows = 3;
    }

    static class Stamped {

        @TestMax(value = 10, stamped = true, severity = RulePolicy.Severity.LOG)
        public int rows = 3;
    }

    private final Config config = Config.inMemory();

    private final RuleEvaluator evaluator = RuleEvaluator.of(AnnotationRuleEngine.INSTANCE);

    @Test
    void anAcceptedValueFindsNothing() {
        final RuleEvaluation evaluation = evaluate(evaluator, Bounded.class, new Bounded(), 5,
                ValueSource.FILE);

        assertTrue(evaluation.findings().isEmpty());
        assertEquals(5, evaluation.value());
        assertFalse(evaluation.corrected());
    }

    @Test
    void aViolationIsReportedAtThePathItWasEvaluatedAt() {
        final RuleEvaluation evaluation = evaluate(evaluator, Bounded.class, new Bounded(), 99,
                ValueSource.FILE);

        final List<RuleFinding> findings = evaluation.findings();
        assertEquals(1, findings.size());
        // The site's own path is the field name; the caller put the value somewhere else, and the message
        // has to name the key the reader will actually look for.
        assertEquals("Settings.rows", findings.get(0).violation().path());
        assertEquals("Rule @TestMax(10) at 'Settings.rows' rejects the file value '99'. Fix the value in "
                + "the file, or relax the rule on Bounded.rows.", findings.get(0).message());
    }

    @Test
    void whatAViolationCostsComesFromTheOriginAndThePolicy() {
        assertEquals(RulePolicy.Severity.REPORT,
                evaluate(evaluator, Bounded.class, new Bounded(), 99, ValueSource.FILE)
                        .findings().get(0).severity());

        // No bind to defer to, so file data the policy said nothing about is treated leniently - while the
        // entity breaking its own rule keeps the escalation that makes it a code defect.
        assertEquals(RulePolicy.Severity.THROW,
                evaluate(evaluator, Bounded.class, new Bounded(), 99, ValueSource.DEFAULT)
                        .findings().get(0).severity());

        final RuleEvaluator logging = evaluator.withPolicy(
                RulePolicy.defaults().withSeverity(RulePolicy.Severity.LOG));
        assertEquals(RulePolicy.Severity.LOG,
                evaluate(logging, Bounded.class, new Bounded(), 99, ValueSource.FILE)
                        .findings().get(0).severity());
    }

    @Test
    void anEngineStampOutranksThePolicy() {
        assertEquals(RulePolicy.Severity.LOG,
                evaluate(evaluator, Stamped.class, new Stamped(), 99, ValueSource.DEFAULT)
                        .findings().get(0).severity());
    }

    @Test
    void aCorrectionRewritesTheFieldAndIsTheValueThatSurvives() {
        final Clamped entity = new Clamped();
        final RuleEvaluator correcting = evaluator.withPolicy(RulePolicy.defaults().withCorrections(true));

        final RuleEvaluation evaluation = evaluate(correcting, Clamped.class, entity, 99, ValueSource.FILE);

        assertTrue(evaluation.corrected());
        assertEquals(10, evaluation.value());
        assertEquals(10, entity.rows);
        assertEquals(1, evaluation.findings().size(), "correcting does not silence");
    }

    @Test
    void aHandlerThatFailsNamesTheRuleAndThePath() {
        final RuleEvaluator broken = RuleEvaluator.of(context -> {
            throw new IllegalStateException("no world loaded");
        });

        final BindException failure = assertThrows(BindException.class,
                () -> evaluate(broken, Bounded.class, new Bounded(), 99, ValueSource.FILE));

        assertEquals("rule @TestMax(10) at 'Settings.rows' failed: no world loaded", failure.getMessage());
    }

    @Test
    void anOwnerlessValueIsStillJudged() {
        final RuleEvaluation evaluation = evaluate(evaluator, Bounded.class, null, 99, ValueSource.FILE);

        assertEquals(1, evaluation.findings().size());
        assertTrue(evaluation.findings().get(0).message().contains("relax the rule on Bounded.rows"));
    }

    @Test
    void aMissingArgumentSaysWhatToPassInstead() {
        final RuleSite site = RuleModel.of(Bounded.class).get(0);
        final ConfigSection at = new ConfigSection(config, "Settings.rows");

        assertEquals("RuleEvaluator.evaluate needs 'site': take one from RuleModel.of(type) - a rule someone "
                        + "declared is the only thing there is to judge.",
                assertThrows(IllegalArgumentException.class,
                        () -> evaluator.evaluate(null, at, 5, ValueSource.FILE, new Bounded())).getMessage());

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(site, null, 5, ValueSource.FILE, new Bounded()))
                .getMessage().contains("config.getConfigSection(path)"));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(site, at, 5, null, new Bounded()))
                .getMessage().contains("ValueSource.FILE"));
    }

    private RuleEvaluation evaluate(final RuleEvaluator evaluator, final Class<?> type, final Object owner,
                                    final Object value, final ValueSource source) {
        return evaluator.evaluate(RuleModel.of(type).get(0), new ConfigSection(config, "Settings.rows"),
                value, source, owner);
    }
}
