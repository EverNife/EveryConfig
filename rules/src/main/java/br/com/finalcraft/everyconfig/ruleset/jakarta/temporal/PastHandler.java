package br.com.finalcraft.everyconfig.ruleset.jakarta.temporal;

import br.com.finalcraft.everyconfig.rule.RuleSite;

import java.util.Collections;
import java.util.List;

/** {@code @Past}: the value has to sit in the past relative to the moment of the bind. No arguments. */
public final class PastHandler extends TemporalSide {

    @Override
    boolean accepts(final int position) {
        return position < 0;
    }

    @Override
    String expectation() {
        return "in the past";
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("Must be in the past.");
    }
}
