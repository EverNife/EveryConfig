package br.com.finalcraft.everyconfig.rule;

import br.com.finalcraft.everyconfig.config.section.ConfigSection;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;

/**
 * What an engine sees for ONE site during one bind: the declaration, the value that landed there, where it
 * came from, and the config around it. Immutable apart from {@link #correct(Object)}, which is an explicit
 * mutation; built by the bind, never by a consumer.
 */
public final class RuleContext {

    private final RuleSite site;
    private final RulePhase phase;
    private final Object value;
    private final Object owner;
    private final ValueSource source;
    private final ConfigSection section;
    private final RuleReport report;
    private final RulePolicy policy;
    private final boolean strictCoercion;

    RuleContext(final RuleSite site, final RulePhase phase, final Object value, final Object owner,
                final ValueSource source, final ConfigSection section, final RuleReport report,
                final RulePolicy policy, final boolean strictCoercion) {
        this.site = site;
        this.phase = phase;
        this.value = value;
        this.owner = owner;
        this.source = source;
        this.section = section;
        this.report = report;
        this.policy = policy;
        this.strictCoercion = strictCoercion;
    }

    public RuleSite site() {
        return site;
    }

    public RulePhase phase() {
        return phase;
    }

    /** The bound value at the site: the field's content, the entity itself for a type rule, or the method's
     *  return value. */
    @Nullable
    public Object value() {
        return value;
    }

    /** The instance declaring the member — the entity itself for a type rule. */
    public Object owner() {
        return owner;
    }

    public ValueSource source() {
        return source;
    }

    /** A section at the site's path: the gateway to siblings, to the tree and to the owning config. */
    public ConfigSection section() {
        return section;
    }

    public RuleReport report() {
        return report;
    }

    /**
     * The severity the active policy resolves for a violation of the given origin — what the bind will do
     * when the violation does not override it. A handler that wants to treat its finding the way file data
     * is treated (a presence rule, whose violation is always default-sourced by construction) stamps
     * {@code severityFor(ValueSource.FILE)} on it instead of reaching into the policy.
     */
    public RulePolicy.Severity severityFor(final ValueSource source) {
        if (source == ValueSource.DEFAULT) {
            return policy.defaultViolations();
        }
        if (policy.severity() != null) {
            return policy.severity();
        }
        return strictCoercion ? RulePolicy.Severity.THROW : RulePolicy.Severity.REPORT;
    }

    /**
     * Rewrite this site's field on the owning instance. Returns whether it was applied: false when the
     * policy has corrections switched off, when the site has no field to set (a type or method rule), or
     * when the field rejects the value.
     *
     * <p>Correcting does not silence: the handler still reports the violation, so what changed is visible
     * to the caller.
     */
    public boolean correct(@Nullable final Object newValue) {
        if (!policy.applyCorrections()) {
            return false;
        }
        final Field target = site.field();
        if (target == null || owner == null) {
            return false;
        }
        try {
            target.setAccessible(true);
            target.set(owner, newValue);
            return true;
        } catch (final Exception notSettable) {
            return false;
        }
    }
}
