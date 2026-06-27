package br.com.finalcraft.everyconfig.binding;

import java.util.List;

/**
 * Opt-in interface for a bound entity that wants to receive the load issues collected while binding it.
 * The issues are pushed in right after binding and before {@code @PostInject} runs.
 */
public interface LoadIssueAware {

    void setLoadIssues(List<LoadIssue> issues);
}
