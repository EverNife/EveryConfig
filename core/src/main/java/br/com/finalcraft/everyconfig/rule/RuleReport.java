package br.com.finalcraft.everyconfig.rule;

/** Where an engine records what it found while evaluating a site. The bind supplies it and decides, after
 *  every site has been seen, what each recorded violation costs. */
public interface RuleReport {

    void violation(RuleViolation violation);
}
