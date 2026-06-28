package br.com.finalcraft.everyconfig.io;

import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Duration;

/**
 * Owns durable, crash-safe access to one configuration's bytes. It is format-agnostic: it never parses
 * or emits, it only moves bytes between memory and a durable store and guards against partial writes and
 * lost user edits. Kept separate from the codec (which owns text&lt;-&gt;tree) so the two evolve
 * independently.
 *
 * <p>The back-store makes no policy decision: it never decides to back up or to reload, it exposes the
 * primitives and is driven by the {@code Config} above it.
 */
public interface BackStore {

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

    /** Opens a watcher over this back-store, or a no-op watcher if unsupported. */
    Watcher watch(Duration pollInterval, Runnable onExternalChange);

    /**
     * As {@link #watch(Duration, Runnable)}, but {@code detectInPlaceEdits} asks the watcher to also catch a
     * same-size edit that lands within one coarse mtime tick, by hashing the content each poll instead of
     * only stat-ing it. That costs a full read per poll, so it is opt-in; the default implementation
     * ignores the flag and behaves like the two-argument form.
     */
    default Watcher watch(Duration pollInterval, Runnable onExternalChange, boolean detectInPlaceEdits) {
        return watch(pollInterval, onExternalChange);
    }

    /**
     * How durably {@link #writeAtomic} must land before it returns.
     *
     * <p>{@code OS_CACHE} (the default) returns as soon as the atomic rename is visible to other
     * processes; the new bytes may still live only in the OS page cache, so an OS/power crash right after
     * can lose the write entirely — but never corrupt the previous content, since the rename is atomic.
     * {@code FSYNC} additionally forces the bytes (and the rename) to the storage device before returning,
     * trading throughput for surviving a crash.
     */
    enum Durability { OS_CACHE, FSYNC }

    /**
     * Immutable (mtime, size) tuple, optionally carrying a content hash. An absent file is {@code (0, -1)}.
     * The hash is {@link #NO_HASH} for the cheap stat-only fingerprint and a real CRC32 when content was
     * read; a stat-only fingerprint still compares value-equal to a hashed one on (mtime, size), so the
     * O(1) path stays compatible while two hashed fingerprints additionally distinguish same-size content.
     */
    final class Fingerprint {

        /** Content hash sentinel meaning "not computed"; such a fingerprint compares on (mtime, size) only. */
        public static final long NO_HASH = 0L;

        public static final Fingerprint ABSENT = new Fingerprint(0L, -1L);

        public final long mtime;
        public final long size;
        public final long hash;

        public Fingerprint(final long mtime, final long size) {
            this(mtime, size, NO_HASH);
        }

        public Fingerprint(final long mtime, final long size, final long hash) {
            this.mtime = mtime;
            this.size = size;
            this.hash = hash;
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
            if (mtime != other.mtime || size != other.size) {
                return false;
            }
            // The content hash refines the comparison only when both sides carry one, so a stat-only
            // fingerprint stays equal to a hashed one on (mtime, size) and the cheap path is unaffected.
            if (hash != NO_HASH && other.hash != NO_HASH) {
                return hash == other.hash;
            }
            return true;
        }

        @Override
        public int hashCode() {
            // Equality can fall back to (mtime, size) when a hash is absent, so the hash must NOT enter the
            // hashCode — equal fingerprints must always share one.
            return (int) (mtime * 31 + size);
        }

        @Override
        public String toString() {
            return "Fingerprint(mtime=" + mtime + ", size=" + size + ", hash=" + hash + ")";
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
