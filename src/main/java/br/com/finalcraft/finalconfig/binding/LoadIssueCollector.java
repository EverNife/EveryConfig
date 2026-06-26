package br.com.finalcraft.finalconfig.binding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Accumulates load issues for the duration of a single bind. The thread-local is installed by
 * {@link #begin()}, written by the lenient problem handler via {@link #record(LoadIssue)}, and drained by
 * {@link #end()} in a finally block — it never outlives one synchronous bind, so it is safe and tiny.
 */
final class LoadIssueCollector {

    private static final ThreadLocal<List<LoadIssue>> CURRENT = new ThreadLocal<>();

    private LoadIssueCollector() {
    }

    static void begin() {
        CURRENT.set(new ArrayList<LoadIssue>());
    }

    static void record(final LoadIssue issue) {
        final List<LoadIssue> list = CURRENT.get();
        if (list != null) {
            list.add(issue);
        }
    }

    static List<LoadIssue> end() {
        final List<LoadIssue> list = CURRENT.get();
        CURRENT.remove();
        return list == null ? Collections.<LoadIssue>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(list));
    }
}
