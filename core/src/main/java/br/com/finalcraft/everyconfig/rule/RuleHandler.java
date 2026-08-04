package br.com.finalcraft.everyconfig.rule;

import java.util.Collections;
import java.util.List;

/**
 * Handles one rule kind — the code behind a {@link ConfigRule}-marked annotation.
 *
 * <p>An implementation must be stateless and thread-safe: {@link AnnotationRuleEngine} keeps ONE instance
 * per handler class and hands it every site of every config, concurrently. An idempotent internal cache (a
 * compiled pattern, a resolved enum set) is fine; per-bind state is not. It also needs a public no-argument
 * constructor, since the engine instantiates it reflectively.
 */
public interface RuleHandler {

    /** Evaluate the site the context describes, recording what it finds through {@link RuleContext#report()}. */
    void check(RuleContext context);

    /**
     * File documentation for a site carrying this rule, one entry per comment line; empty by default, so the
     * interface can gain members without breaking an implementation. Must be deterministic — a text that
     * varies between calls re-dirties the file on every save.
     */
    default List<String> describe(final RuleSite site) {
        return Collections.emptyList();
    }

    /** The inert handler: the rule is a declared fact only, left to whichever engine claims the annotation. */
    final class None implements RuleHandler {

        @Override
        public void check(final RuleContext context) {
        }
    }
}
