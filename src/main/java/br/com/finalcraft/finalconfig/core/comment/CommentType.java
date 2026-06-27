package br.com.finalcraft.finalconfig.core.comment;

/**
 * Where a comment sits relative to its key. BLOCK = the comment lines above a key; SIDE = the trailing
 * comment after a value on the same line.
 */
public enum CommentType {
    BLOCK,
    SIDE
}
