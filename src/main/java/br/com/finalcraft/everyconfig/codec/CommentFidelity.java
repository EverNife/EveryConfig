package br.com.finalcraft.everyconfig.codec;

/**
 * How faithfully a {@link Codec}'s on-disk format can ROUND-TRIP comments.
 *
 * <p>Fidelity is measured on the IN-BAND round-trip only — i.e. "can a comment present in the file
 * survive load -&gt; mutate -&gt; save and remain attached to its path?" — NOT on a format's total
 * comment expressibility. A format declares exactly one fidelity; the comment reconciliation engine
 * consults it to decide whether to capture, seed, and re-emit comments in-band at all.
 */
public enum CommentFidelity {

    /**
     * Comments survive a full load -&gt; mutate -&gt; save round-trip, attached to the paths they
     * belong to (YAML, TOML). The codec MUST also implement {@link CommentAware}.
     */
    LOSSLESS,

    /**
     * The format carries comments but cannot reliably re-attach every one to its original path on
     * round-trip (e.g. only a file header is preserved, or trailing/floating comments are dropped).
     * Seeded defaults are still written; user edits to recognized positions are preserved, the rest
     * is best-effort.
     */
    LOSSY,

    /**
     * The format has no in-band comment syntax that round-trips (strict RFC JSON). Comment seeds and
     * the comment overlay are ignored at write time. This says nothing about a ONE-WAY sidecar
     * documentation projection, which never participates in a round-trip and so does not contradict
     * NONE (see {@link Codec#writesSidecarDoc()}).
     */
    NONE
}
