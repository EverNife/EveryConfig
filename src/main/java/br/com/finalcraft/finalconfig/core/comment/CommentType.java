package br.com.finalcraft.finalconfig.core.comment;

/**
 * FinalConfig's own comment type (replacing {@code org.simpleyaml.configuration.comments.CommentType}).
 * BLOCK = comment lines above a key; SIDE = trailing comment after a value.
 */
public enum CommentType {
    BLOCK,
    SIDE
}
