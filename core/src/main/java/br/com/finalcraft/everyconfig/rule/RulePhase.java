package br.com.finalcraft.everyconfig.rule;

/** Which direction of a bind is being checked: the tree read into an entity, or the entity projected back. */
public enum RulePhase {

    /** Reading: the entity has just been bound from the tree. */
    VALIDATE,

    /** Writing: the entity is about to be projected into the tree, so a correction still reaches the file. */
    NORMALIZE
}
