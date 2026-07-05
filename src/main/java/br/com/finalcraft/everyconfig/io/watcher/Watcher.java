package br.com.finalcraft.everyconfig.io.watcher;

/**
 * A running observer of the durable store; closing it stops the observation.
 */
public interface Watcher extends AutoCloseable {

    void start();

    /**
     * Re-baseline to a known fingerprint (the one just written), so the next check does not treat our
     * own save as external. Passing the exact written fingerprint — not a fresh probe — closes the
     * race where an external edit lands between our write and a re-probe.
     */
    void refreshSnapshot(Fingerprint justWrote);

    @Override
    void close();
}
