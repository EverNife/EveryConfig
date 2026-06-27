package br.com.finalcraft.everyconfig.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;

/**
 * A {@link Backend} over a single file. Writes go through a unique sibling temp file plus an atomic
 * rename, so a concurrent reader never sees a torn or truncated file. On filesystems without atomic
 * rename it falls back to a plain replace (still safe against truncation, since the temp is written
 * whole first).
 *
 * <p>"Atomic" here means crash-safe against torn reads and truncation. Whether a write also survives an
 * OS/power crash is the {@link Durability} knob: {@code OS_CACHE} (default) does no fsync — a crash can
 * lose the write entirely but never corrupt the previous content — while {@code FSYNC} forces the bytes
 * and the rename to the device before returning.
 */
public final class AtomicFileBackend implements Backend {

    private static final Charset CS = StandardCharsets.UTF_8;

    private final Path filePath;
    private final Durability durability;

    public AtomicFileBackend(final Path filePath) {
        this(filePath, Durability.OS_CACHE);
    }

    public AtomicFileBackend(final Path filePath, final Durability durability) {
        this.filePath = filePath;
        this.durability = durability != null ? durability : Durability.OS_CACHE;
    }

    public Path path() {
        return filePath;
    }

    @Override
    public String describe() {
        return filePath.toAbsolutePath().toString();
    }

    @Override
    public Charset charset() {
        return CS;
    }

    @Override
    public boolean exists() {
        return Files.exists(filePath);
    }

    @Override
    public long lastModified() {
        return exists() ? filePath.toFile().lastModified() : 0L;
    }

    @Override
    public long size() {
        try {
            return exists() ? Files.size(filePath) : -1L;
        } catch (final IOException e) {
            return -1L;
        }
    }

    @Override
    public Fingerprint fingerprint() {
        return new Fingerprint(lastModified(), size());
    }

    @Override
    public byte[] readBytes() throws IOException {
        return exists() ? Files.readAllBytes(filePath) : null;
    }

    @Override
    public Fingerprint writeAtomic(final byte[] data) throws IOException {
        final Path parent = filePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // A UNIQUE sibling temp (same directory as the target). Same directory because an atomic rename
        // only works within one filesystem; a unique name avoids any collision on the temp itself.
        final Path tmp = Files.createTempFile(parent != null ? parent : Paths.get("."),
                filePath.getFileName().toString(), ".tmpfc");
        try {
            writeTemp(tmp, data);
            try {
                Files.move(tmp, filePath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                // Some network/union mounts cannot rename atomically; a plain replace is still safe
                // against truncation because a complete temp was written first.
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
            if (durability == Durability.FSYNC) {
                // The content force above persists the bytes; forcing the directory persists the rename
                // itself, so a crash cannot leave the file pointing at the pre-write inode.
                fsyncDir(parent != null ? parent : filePath.toAbsolutePath().getParent());
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return new Fingerprint(filePath.toFile().lastModified(), data.length);
    }

    /** Writes the temp file, forcing its bytes to the storage device first when {@code FSYNC} is asked. */
    private void writeTemp(final Path tmp, final byte[] data) throws IOException {
        if (durability != Durability.FSYNC) {
            Files.write(tmp, data, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            return;
        }
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(ByteBuffer.wrap(data));
            ch.force(true); // flush content + metadata to the device before the temp becomes the target
        }
    }

    /** Best-effort directory fsync. Many platforms (notably Windows) cannot fsync a directory handle; the
     *  content force is the portable guarantee, so a failure here is tolerated rather than propagated. */
    private static void fsyncDir(final Path dir) {
        if (dir == null) {
            return;
        }
        try (FileChannel dch = FileChannel.open(dir, StandardOpenOption.READ)) {
            dch.force(true);
        } catch (final IOException ignored) {
            // directory sync unsupported on this platform; the file-content force already persisted the data
        }
    }

    @Override
    public String backupUnparseable() throws IOException {
        if (!Files.exists(filePath)) {
            return null;
        }
        final Path bak = filePath.resolveSibling(filePath.getFileName() + ".bak");
        if (Files.exists(bak)) {
            final FileTime bakTime = Files.getLastModifiedTime(bak);
            final FileTime srcTime = Files.getLastModifiedTime(filePath);
            if (bakTime.toMillis() >= srcTime.toMillis()) {
                return bak.toString(); // an existing, newer-or-equal backup is the better rescue; keep it
            }
        }
        Files.copy(filePath, bak, StandardCopyOption.REPLACE_EXISTING);
        return bak.toString();
    }

    @Override
    public Watcher watch(final Duration pollInterval, final Runnable onExternalChange) {
        return new FilePollWatcher(filePath, pollInterval, onExternalChange);
    }
}
