package br.com.finalcraft.everyconfig.config;

/** Unchecked failure of a config's durable I/O (load, save, reload). */
public class ConfigIOException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigIOException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
