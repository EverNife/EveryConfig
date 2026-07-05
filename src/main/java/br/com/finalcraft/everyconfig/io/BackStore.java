package br.com.finalcraft.everyconfig.io;

import br.com.finalcraft.everyconfig.io.watcher.Watcher;
import br.com.finalcraft.everyconfig.io.watcher.Fingerprint;

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
    Watcher watch(Duration pollInterval, Runnable onExternalChange, boolean detectInPlaceEdits);

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

}
