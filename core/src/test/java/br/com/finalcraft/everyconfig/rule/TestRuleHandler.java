package br.com.finalcraft.everyconfig.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Records every site it is handed, so the built-in engine's delegation and handler caching are observable. */
final class TestRuleHandler implements RuleHandler {

    /** Paths checked so far, newest last; a suite clears it before asserting. */
    static final List<String> CHECKED = Collections.synchronizedList(new ArrayList<String>());

    /** How many times this handler class was instantiated — one, however many sites carry the rule. */
    static int instances = 0;

    TestRuleHandler() {
        instances++;
    }

    @Override
    public void check(final RuleContext context) {
        CHECKED.add(context.site().path());
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("rule: " + ((TestRule) site.rule()).value());
    }
}
