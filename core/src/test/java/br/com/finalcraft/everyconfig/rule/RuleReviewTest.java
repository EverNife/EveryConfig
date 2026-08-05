package br.com.finalcraft.everyconfig.rule;

import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.binding.BindOptions;
import br.com.finalcraft.everyconfig.binding.BindResult;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.codec.jackson.YamlCodec;
import br.com.finalcraft.everyconfig.config.Config;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entity's last word on its own data. A declared rule states a fact about a value; the review is where a
 * runtime exception to that fact becomes expressible — a magic value no annotation could list, an invariant
 * that depends on another config — so its decisions outrank the engine and the policy alike.
 */
class RuleReviewTest {

    /** Takes the file's word for it, whatever the bind's strictness would otherwise cost. */
    static class Accepting implements RuleReviewer {

        @TestMax(100)
        public int limit = 5;

        @Override
        public void reviewRules(final RuleReviewContext review) {
            for (final RuleViolation violation : review.violations()) {
                review.accept(violation);
            }
        }
    }

    /** The engine priced this one THROW; the entity knows better. */
    static class AcceptingStamped implements RuleReviewer {

        @TestMax(value = 100, stamped = true, severity = RulePolicy.Severity.THROW)
        public int limit = 5;

        @Override
        public void reviewRules(final RuleReviewContext review) {
            for (final RuleViolation violation : review.violations()) {
                review.accept(violation);
            }
        }
    }

    /** Argues a stamped failure down to something merely worth reporting. */
    static class Downgrading implements RuleReviewer {

        @TestMax(value = 100, stamped = true, severity = RulePolicy.Severity.THROW)
        public int limit = 5;

        @Override
        public void reviewRules(final RuleReviewContext review) {
            for (final RuleViolation violation : review.violations()) {
                review.override(violation, RulePolicy.Severity.REPORT);
            }
        }
    }

    /** ...and, on another day, a mere report up to a failure. */
    static class Escalating implements RuleReviewer {

        @TestMax(100)
        public int limit = 5;

        @Override
        public void reviewRules(final RuleReviewContext review) {
            for (final RuleViolation violation : review.violations()) {
                review.override(violation, RulePolicy.Severity.THROW);
            }
        }
    }

    /** Fixes the value itself: explicit intent, not the automatic rewriting the policy gates. */
    static class Correcting implements RuleReviewer {

        @TestMax(100)
        public int limit = 5;

        @Override
        public void reviewRules(final RuleReviewContext review) {
            for (final RuleViolation violation : review.violations()) {
                review.correct(violation, 100);
            }
        }
    }

    /** No rule annotation anywhere: its whole check is its own logic, and it must still run. */
    static class Reporting {

        public String databaseId = "";

        @RuleReview
        void databaseMustBeKnown(final RuleReviewContext review) {
            review.report("databaseId", "no database named '" + databaseId + "' is configured");
        }
    }

    /** The cross-config case: the answer lives in another Config, so only code can ask it. */
    static class Failing {

        public String databaseId = "";

        @RuleReview
        void databaseMustBeEnabled(final RuleReviewContext review) {
            review.fail("databaseId", "storage.yml declares '" + databaseId
                    + "' as disabled; pick an enabled database or enable it there");
        }
    }

    /** Records which direction of the bind it was reviewed in. */
    static class PhaseAware implements RuleReviewer {

        public int limit = 5;

        @JsonIgnore
        public RulePhase seen;

        @Override
        public void reviewRules(final RuleReviewContext review) {
            seen = review.phase();
        }
    }

    static class Child implements RuleReviewer {

        @TestMax(100)
        public int limit = 5;

        @JsonIgnore
        public int seenCount = -1;

        @JsonIgnore
        public String seenPath;

        @Override
        public void reviewRules(final RuleReviewContext review) {
            seenCount = review.violations().size();
            seenPath = review.section().getPath();
        }
    }

    static class Parent implements RuleReviewer {

        public Child child = new Child();

        @JsonIgnore
        public int seenCount = -1;

        @Override
        public void reviewRules(final RuleReviewContext review) {
            seenCount = review.violations().size();
        }
    }

    static class ReviewBase implements RuleReviewer {

        @JsonIgnore
        public final List<String> order = new ArrayList<>();

        @RuleReview
        void inherited(final RuleReviewContext review) {
            order.add("base-inherited");
        }

        @Override
        public void reviewRules(final RuleReviewContext review) {
            order.add("interface");
        }
    }

    static class ReviewSub extends ReviewBase {

        @Override
        @RuleReview
        void inherited(final RuleReviewContext review) {
            order.add("sub-inherited");
        }

        @RuleReview
        void own(final RuleReviewContext review) {
            order.add("own");
        }
    }

    static class Exploding {

        public int value = 1;

        @RuleReview
        void checkTheWorld(final RuleReviewContext review) {
            throw new IllegalStateException("kaboom");
        }
    }

    /** Decides about a violation it was never handed, which is exactly what must have no effect. */
    static class Meddling implements RuleReviewer {

        @TestMax(100)
        public int limit = 5;

        @Override
        public void reviewRules(final RuleReviewContext review) {
            final RuleViolation foreign = RuleViolation.ofEntity("limit", ValueSource.FILE, 150, "not mine");
            review.accept(foreign);
            review.override(foreign, RulePolicy.Severity.REPORT);
            review.correct(foreign, 1);
        }
    }

    private final YamlCodec yaml = new YamlCodec();

    private Config broken() {
        final Config config = Config.inMemory(yaml);
        config.setValue("limit", 150);
        return config;
    }

    @Test
    void acceptSuppressesAThrowFromStrictnessAndFromTheEngineAlike() {
        final BindOptions strict = BindOptions.defaults().withCoercion(BindOptions.Coercion.STRICT);
        final BindResult<Accepting> underStrict = broken().bind(Accepting.class, yaml, strict).readResult("");
        assertTrue(underStrict.issues().isEmpty(), "an accepted violation leaves no trace, got "
                + underStrict.issues());
        assertEquals(150, underStrict.value().limit, "accepting keeps the value the file asked for");

        final BindResult<AcceptingStamped> stamped = broken().loadAsResult(AcceptingStamped.class, yaml);
        assertTrue(stamped.issues().isEmpty(), "the review outranks the engine's own stamp, got "
                + stamped.issues());
    }

    @Test
    void overrideRepricesInBothDirections() {
        final BindResult<Downgrading> down = broken().loadAsResult(Downgrading.class, yaml);
        assertEquals(1, down.issues().size(), "a stamped THROW argued down to a report");

        assertThrows(BindException.class, () -> broken().loadAs(Escalating.class, yaml),
                "a mere report argued up to a failure");
    }

    @Test
    void aReviewCorrectionIsNotSubjectToTheAutomaticCorrectionGate() {
        final Config config = broken();
        assertFalse(config.getRulePolicy().applyCorrections(), "the gate is off, as it is out of the box");

        final BindResult<Correcting> result = config.loadAsResult(Correcting.class, yaml);

        assertEquals(100, result.value().limit);
        assertEquals(100, config.getInt("limit"), "a file-sourced fix reaches the canonical tree");
        assertTrue(config.hasRuleFixes());
        assertEquals(1, result.issues().size(), "correcting alone does not suppress the report");
    }

    @Test
    void reportAndFailRaiseTheEntitysOwnFindings() {
        final Config config = Config.inMemory(yaml);
        config.setValue("databaseId", "ghost");

        final BindResult<Reporting> reported = config.loadAsResult(Reporting.class, yaml);
        assertEquals(1, reported.issues().size());
        final LoadIssue issue = reported.issues().get(0);
        assertEquals(LoadIssue.Kind.RULE, issue.kind());
        assertEquals("databaseId", issue.key());
        assertNull(issue.violation().rule(), "no annotation is behind an entity's own finding");
        assertEquals(Reporting.class, issue.targetType());
        assertEquals(RuleViolation.ENTITY_MESSAGE_KEY, issue.violation().messageKey());

        final BindException thrown = assertThrows(BindException.class,
                () -> config.loadAs(Failing.class, yaml));
        assertTrue(thrown.getMessage().contains("Rule review at 'databaseId'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("pick an enabled database"), thrown.getMessage());
    }

    @Test
    void aReviewRunsWithNoViolationsAtAllAndInBothDirections() {
        final Config config = Config.inMemory(yaml);

        final PhaseAware read = config.loadAs(PhaseAware.class, yaml);
        assertEquals(RulePhase.VALIDATE, read.seen, "reading reviews even when nothing was found");

        final PhaseAware written = new PhaseAware();
        config.bind(PhaseAware.class, yaml).write("", written);
        assertEquals(RulePhase.NORMALIZE, written.seen, "and so does writing");
    }

    @Test
    void aNestedOwnerReviewsItsOwnFindingsAndTheRootDoesNotSeeThem() {
        final Config config = Config.inMemory(yaml);
        config.setValue("child.limit", 150);

        final BindResult<Parent> result = config.loadAsResult(Parent.class, yaml);

        assertEquals(1, result.value().child.seenCount, "the nested entity reviews what was found on it");
        assertEquals("child", result.value().child.seenPath, "at its own path");
        assertEquals(0, result.value().seenCount, "the root sees only its own; the holistic view is @PostLoad");
        assertEquals("child.limit", result.issues().get(0).key());
    }

    @Test
    void annotatedMethodsRunBeforeTheInterfaceAndAnOverriddenOneRunsOnce() {
        final ReviewSub entity = Config.inMemory(yaml).loadAs(ReviewSub.class, yaml);
        assertEquals(Arrays.asList("sub-inherited", "own", "interface"), entity.order);
    }

    @Test
    void aFailingReviewSurfacesAsABindExceptionNamingItAndThePath() {
        final Config config = Config.inMemory(yaml);
        config.setValue("deep.value", 1);

        final BindException thrown = assertThrows(BindException.class,
                () -> config.bind(Exploding.class, yaml).read("deep"));

        assertEquals("@RuleReview 'checkTheWorld' at 'deep' failed: kaboom", thrown.getMessage());
    }

    @Test
    void aDecisionAboutAnotherInstancesViolationDoesNothing() {
        final Config config = broken();
        final BindResult<Meddling> result = config.loadAsResult(Meddling.class, yaml);

        assertEquals(1, result.issues().size(), "the real finding is untouched by the foreign decisions");
        assertEquals(150, result.value().limit, "and so is the value");
        assertFalse(config.hasRuleFixes());
    }
}
