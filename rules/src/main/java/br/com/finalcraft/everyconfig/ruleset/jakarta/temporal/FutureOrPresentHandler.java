package br.com.finalcraft.everyconfig.ruleset.jakarta.temporal;

import br.com.finalcraft.everyconfig.rule.RuleSite;

import java.util.Collections;
import java.util.List;

/** {@code @FutureOrPresent}: the value has to sit now or in the future relative to the moment of the bind. No arguments. */
public final class FutureOrPresentHandler extends TemporalSide {

    @Override
    boolean accepts(final int position) {
        return position >= 0;
    }

    @Override
    String expectation() {
        return "now or in the future";
    }

    @Override
    public List<String> describe(final RuleSite site) {
        return Collections.singletonList("Must not be in the past.");
    }
}
