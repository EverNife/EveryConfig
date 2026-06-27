package br.com.finalcraft.everyconfig.binding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of a lenient bind/read: the bound value plus the {@link LoadIssue}s collected for THAT call.
 * Returning them together lets the issues travel with the value instead of living in a stateful getter that
 * a later bind/read on the same binder or config would overwrite. The issue list is an unmodifiable
 * snapshot taken at construction.
 *
 * <p>{@link #value()} may be null when the target type cannot be constructed without data.
 */
public final class BindResult<T> {

    private final T value;
    private final List<LoadIssue> issues;

    public BindResult(final T value, final List<LoadIssue> issues) {
        this.value = value;
        this.issues = issues == null
                ? Collections.<LoadIssue>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(issues));
    }

    public T value() {
        return value;
    }

    public List<LoadIssue> issues() {
        return issues;
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
