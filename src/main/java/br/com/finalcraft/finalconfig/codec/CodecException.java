package br.com.finalcraft.finalconfig.codec;

/**
 * Unchecked wrapper for any encode/decode failure raised by a {@link Codec}. Thrown on malformed
 * input ({@link Codec#readTree}), on a serialization failure, and when a structural contract is
 * violated (e.g. a populated container handed to {@link CommentAware#writeScalar}). The backend turns
 * a read-time failure into its recovery flow.
 */
public class CodecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CodecException(final String message) {
        super(message);
    }

    public CodecException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public CodecException(final Throwable cause) {
        super(cause);
    }
}
