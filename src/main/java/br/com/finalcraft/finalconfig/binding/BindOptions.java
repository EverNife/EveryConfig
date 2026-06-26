package br.com.finalcraft.finalconfig.binding;

/**
 * Knobs for a bind: how strictly to coerce values, and what to do with keys in the file that the bound
 * schema no longer declares. Immutable; use the {@code with...} methods to derive a variant.
 */
public final class BindOptions {

    /** How a value that does not match its target type is handled. */
    public enum Coercion {
        /** First mismatch throws a {@link BindException}. */
        STRICT,
        /** A bad value is recorded as a {@link LoadIssue} and skipped; the bind continues. */
        LENIENT
    }

    /** What happens to a file key the bound schema does not declare. */
    public enum ObsoletePolicy {
        /** Keep it untouched (the safe default; the tree always wins). */
        PRESERVE,
        /** Strip it from the tree during the merge (opt-in; destroys data the schema no longer knows). */
        REMOVE
    }

    private final Coercion coercion;
    private final ObsoletePolicy obsoletePolicy;

    private BindOptions(final Coercion coercion, final ObsoletePolicy obsoletePolicy) {
        this.coercion = coercion;
        this.obsoletePolicy = obsoletePolicy;
    }

    public static BindOptions defaults() {
        return new BindOptions(Coercion.LENIENT, ObsoletePolicy.PRESERVE);
    }

    public Coercion coercion() {
        return coercion;
    }

    public ObsoletePolicy obsoletePolicy() {
        return obsoletePolicy;
    }

    public BindOptions withCoercion(final Coercion newCoercion) {
        return new BindOptions(newCoercion, obsoletePolicy);
    }

    public BindOptions withObsoletePolicy(final ObsoletePolicy newPolicy) {
        return new BindOptions(coercion, newPolicy);
    }
}
