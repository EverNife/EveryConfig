package br.com.finalcraft.everyconfig.binding;

import br.com.finalcraft.everyconfig.config.section.ConfigSection;

import java.util.List;

/**
 * The single context handed to every lifecycle hook — both the {@code @PreLoad}/{@code @PostLoad}/
 * {@code @PreSave}/{@code @PostSave} methods and the {@link ConfigLifecycle} interface. It carries the
 * {@link ConfigSection} the entity is bound at (the gateway to siblings, the owning {@code Config} and the
 * raw tree via {@link ConfigSection#getConfig()}) and the {@link LoadIssue}s collected for the bind.
 *
 * <p>{@link #issues()} is populated only during {@code @PostLoad} — the one phase where a bind has produced
 * issues; it is an empty list for the pre-load and the save phases.
 */
public final class ConfigContext {

    private final ConfigSection section;
    private final List<LoadIssue> issues;

    public ConfigContext(final ConfigSection section, final List<LoadIssue> issues) {
        this.section = section;
        this.issues = issues;
    }

    /** The section the entity is bound at; reach the {@code Config} via {@link ConfigSection#getConfig()}. */
    public ConfigSection section() {
        return section;
    }

    /** Issues collected for the bind — populated only during {@code @PostLoad}; empty otherwise. */
    public List<LoadIssue> issues() {
        return issues;
    }
}
