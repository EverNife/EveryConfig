package br.com.finalcraft.finalconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Seeds a comment for a field's key (or, on a type, the file header). The comment is a SEED only: it is
 * written when the path has no comment yet and is never written over a comment the user edited in the
 * file. Each array element is one comment line.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Comment {

    String[] value();
}
