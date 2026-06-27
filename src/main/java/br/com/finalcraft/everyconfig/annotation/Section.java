package br.com.finalcraft.everyconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the dotted placement of a field or type within the document, e.g.
 * {@code @Section("database.pool")}. The canonical way to nest a value under a path; preferred over an
 * ad-hoc prefix on the key.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Section {

    String value();
}
