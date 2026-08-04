package br.com.finalcraft.everyconfig.rule;

import java.util.Collections;
import java.util.List;

/**
 * Applies rules during a bind — the consumer end of the seam, attached per config. Stateless and
 * thread-safe: one engine serves every config that attaches it, concurrently.
 */
public interface RuleEngine {

    /** The annotations this engine understands; the bind shortlists a type's sites through it. */
    default RuleSelector selector() {
        return RuleSelector.CONFIG_RULE_MARKED;
    }

    void apply(RuleContext context);

    /**
     * File documentation for a site, rendered by the comment pass; empty by default, so the interface can
     * gain members without breaking an implementation. Must be deterministic — a text that varies re-dirties
     * the file on every save.
     */
    default List<String> describe(final RuleSite site) {
        return Collections.emptyList();
    }

    /** The engine that does nothing. Attaching it switches the whole subsystem off by identity. */
    RuleEngine NONE = context -> {
    };

    /**
     * Chain two engines by CLAIM: a site the first engine's selector claims is the first engine's — both to
     * apply and to describe — and the second only sees what is left. The composed selector is the union, so
     * the bind still shortlists in one pass.
     */
    static RuleEngine compose(final RuleEngine first, final RuleEngine second) {
        final RuleEngine a = first != null ? first : NONE;
        final RuleEngine b = second != null ? second : NONE;
        if (a == NONE) {
            return b;
        }
        if (b == NONE) {
            return a;
        }
        return new RuleEngine() {

            @Override
            public RuleSelector selector() {
                return RuleSelector.union(a.selector(), b.selector());
            }

            @Override
            public void apply(final RuleContext context) {
                (a.selector().claims(context.site().rule()) ? a : b).apply(context);
            }

            @Override
            public List<String> describe(final RuleSite site) {
                return (a.selector().claims(site.rule()) ? a : b).describe(site);
            }
        };
    }
}
