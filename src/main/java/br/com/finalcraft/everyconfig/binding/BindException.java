package br.com.finalcraft.everyconfig.binding;

/** Unchecked failure of the typed binding layer (bind, merge, annotation resolution, lifecycle hooks). */
public class BindException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BindException(final String message) {
        super(message);
    }

    public BindException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
