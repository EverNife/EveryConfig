package br.com.finalcraft.everyconfig.binding;

import br.com.finalcraft.everyconfig.config.section.ConfigSection;

import java.util.List;

/**
 * The single context handed to every lifecycle hook — both the
 * {@link br.com.finalcraft.everyconfig.annotation.PreLoad @PreLoad}/
 * {@link br.com.finalcraft.everyconfig.annotation.PostLoad @PostLoad}/
 * {@link br.com.finalcraft.everyconfig.annotation.PreSave @PreSave}/
 * {@link br.com.finalcraft.everyconfig.annotation.PostSave @PostSave} methods and the {@link ConfigLifecycle}
 * interface. It carries the {@link ConfigSection} the entity is bound at (the gateway to siblings, the owning
 * {@code Config} and the raw tree via {@link ConfigSection#getConfig()}) and the {@link LoadIssue}s collected
 * for the bind.
 *
 * <p>{@link #issues()} carries whatever the bind found wrong: it is populated during {@code @PostLoad} — a
 * value that would not convert, a rule that rejected one — and during {@code @PostSave} when writing broke a
 * rule. One channel in both directions. The pre phases see an empty list: nothing has been examined yet.
 *
 * @see ConfigLifecycle
 * @see br.com.finalcraft.everyconfig.annotation.PreLoad
 * @see br.com.finalcraft.everyconfig.annotation.PostLoad
 * @see br.com.finalcraft.everyconfig.annotation.PreSave
 * @see br.com.finalcraft.everyconfig.annotation.PostSave
 */
public final class ConfigContext {

    private final ConfigSection section;
    private final List<LoadIssue> issues;

    public ConfigContext(final ConfigSection section, final List<LoadIssue> issues) {
        this.section = section;
        this.issues = issues;
    }

    /** The section the entity is bound at; reach the {@code Config} via {@link ConfigSection#getConfig()}. For a
     *  nested entity (a field, {@code Map} value, or collection element) this is its real sub-path, so the hook
     *  reaches the same slice of the tree a top-level bind would. */
    public ConfigSection section() {
        return section;
    }

    /** What the bind found wrong — coercion and rule issues alike; populated at {@code @PostLoad} and, for a
     *  rule broken on the way out, at {@code @PostSave}. Empty during the pre phases. */
    public List<LoadIssue> issues() {
        return issues;
    }
}
