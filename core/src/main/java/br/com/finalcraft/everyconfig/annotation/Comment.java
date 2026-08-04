package br.com.finalcraft.everyconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents a field's key (or, on a type, the file header). By default ({@link CommentMode#OVERRIDE}) the
 * comment is rewritten on every save, so a change to this text in code reaches existing files; switch to
 * {@link CommentMode#SET_IF_ABSENT} to write it only once and let a user-edited comment win thereafter.
 * Each array element is one comment line.
 *
 * <p>On a field of a NESTED POJO it composes: the comment is seeded at the sub-path where that field's value
 * is written (honoring {@code @Section} the same way the value is relocated), at any depth. Two limits: a
 * type-level {@code @Comment} seeds the file header only for the TOP type (a nested type's class comment is
 * ignored), and the DECLARED field type drives the descent (a runtime subtype's own comments are not seeded).
 * A {@code @Comment} on an element of a {@code List}/{@code Set}/array or a {@code Map} value is out of scope
 * (no stable per-element comment path). A NONE-fidelity codec (e.g. JSON) drops comments entirely.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Comment {

    String[] value();

    CommentMode mode() default CommentMode.OVERRIDE;
}
