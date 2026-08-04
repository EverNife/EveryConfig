package br.com.finalcraft.everyconfig.rule;

/**
 * Where the value a rule judged came from — the difference between bad user data and a code defect, and so
 * the difference between reporting and throwing.
 */
public enum ValueSource {

    /**
     * The tree as loaded carried a node at the site's path. A value the file supplied but the bind then
     * discarded is still FILE: its defect is already a coercion issue, and reclassifying it would make a
     * presence rule fire twice for one cause.
     */
    FILE,

    /** No node at the site's path: the value is the one the freshly constructed entity brought. */
    DEFAULT
}
