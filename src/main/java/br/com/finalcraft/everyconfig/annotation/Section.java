package br.com.finalcraft.everyconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the dotted placement of a field or type within the document, e.g.
 * {@code @Section("database.pool")}. The canonical way to nest a value under a path; preferred over an
 * ad-hoc prefix on the key. Applies to a field of a nested POJO as well, where the path is relative to that
 * POJO's own location. A {@code @Section} field inside a {@code List}/{@code Map} element is unsupported
 * (a collection element has no stable path).
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Section {

    String value();
}
