package br.com.finalcraft.everyconfig.annotation;

/** How a {@link Comment} is applied to a path that may already carry a comment. */
public enum CommentMode {

    /** Always write the comment, overwriting any existing one — code documentation stays current. */
    OVERRIDE,
    /** Write the comment only when the path has none yet, so a user-edited comment is preserved. */
    SET_IF_ABSENT
}
