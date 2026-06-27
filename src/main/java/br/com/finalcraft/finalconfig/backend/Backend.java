package br.com.finalcraft.finalconfig.backend;

import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Duration;

/**
 * Owns durable, crash-safe access to one configuration's bytes. It is format-agnostic: it never parses
 * or emits, it only moves bytes between memory and a durable store and guards against partial writes and
 * lost user edits. Kept separate from the codec (which owns text&lt;-&gt;tree) so the two evolve
 * independently.
 *
 * <p>The backend makes no policy decision: it never decides to back up or to reload, it exposes the
 * primitives and is driven by the {@code Config} above it.
 */
public interface Backend {

    /** Stable identity for logs/snapshots, e.g. the absolute file path or {@code "(in-memory)"}. */
    String describe();

    /** Charset used to decode/encode text. */
    Charset charset();

    /** True if a durable representation currently exists. */
    boolean exists();

    /** Last-modified epoch millis of the durable store, or 0 if it does not exist. */
    long lastModified();

    /** Byte size of the durable store, or -1 if absent. */
    long size();

    /** Cheap (mtime, size) fingerprint of the durable store; the unit a watcher diffs. */
    Fingerprint fingerprint();

    /** Reads the current durable bytes, or null when {@link #exists()} is false. */
    byte[] readBytes() throws IOException;

    /**
     * Atomically publishes {@code data} as the new durable content and returns the fingerprint of exactly
     * the bytes just written. A reader sees either the complete previous content or the complete new
     * content, never a truncated file. Creates parent directories as needed.
     *
     * <p>Returning the post-write fingerprint (rather than re-probing) lets a watcher tell "our own write"
     * apart from an external edit that raced the write.
     */
    Fingerprint writeAtomic(byte[] data) throws IOException;

    /**
     * Copies the current durable content aside as a {@code .bak} sibling before it is overwritten because
     * it could not be parsed. No-op (returns null) when nothing exists to back up. Does not overwrite a
     * {@code .bak} newer than the source, so a later, emptier corruption cannot clobber an earlier rescue.
     */
    String backupUnparseable() throws IOException;

    /** Opens a watcher over this backend, or a no-op watcher if unsupported. */
    Watcher watch(Duration pollInterval, Runnable onExternalChange);

    /** Immutable (mtime, size) pair; an absent file is {@code (0, -1)}. Value-equal by both fields. */
    final class Fingerprint {

        public static final Fingerprint ABSENT = new Fingerprint(0L, -1L);

        public final long mtime;
        public final long size;

        public Fingerprint(final long mtime, final long size) {
            this.mtime = mtime;
            this.size = size;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Fingerprint)) {
                return false;
            }
            final Fingerprint other = (Fingerprint) o;
            return mtime == other.mtime && size == other.size;
        }

        @Override
        public int hashCode() {
            return (int) (mtime * 31 + size);
        }

        @Override
        public String toString() {
            return "Fingerprint(mtime=" + mtime + ", size=" + size + ")";
        }
    }

    /** A running observer of the durable store; closing it stops the observation. */
    interface Watcher extends AutoCloseable {

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
}
