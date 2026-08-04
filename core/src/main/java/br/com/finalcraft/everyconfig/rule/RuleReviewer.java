package br.com.finalcraft.everyconfig.rule;

/** Interface twin of {@link RuleReview}, for an entity that prefers a typed contract to an annotation. It
 *  runs after the entity's {@code @RuleReview} methods, with the same context and the same authority. */
public interface RuleReviewer {

    void reviewRules(RuleReviewContext review);
}
