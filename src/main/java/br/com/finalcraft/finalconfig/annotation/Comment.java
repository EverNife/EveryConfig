package br.com.finalcraft.finalconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents a field's key (or, on a type, the file header). By default ({@link CommentMode#OVERRIDE}) the
 * comment is rewritten on every save, so a change to this text in code reaches existing files; switch to
 * {@link CommentMode#SET_IF_ABSENT} to write it only once and let a user-edited comment win thereafter.
 * Each array element is one comment line.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Comment {

    String[] value();

    CommentMode mode() default CommentMode.OVERRIDE;
}
