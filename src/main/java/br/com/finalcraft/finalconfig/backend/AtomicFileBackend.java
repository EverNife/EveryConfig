package br.com.finalcraft.finalconfig.backend;

import java.io.IOException;
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
 * <p>"Atomic" here means crash-safe against torn reads and truncation — not against power-loss data loss
 * (there is no fsync): a crash can lose the write entirely but can never corrupt the previous content.
 */
public final class AtomicFileBackend implements Backend {

    private static final Charset CS = StandardCharsets.UTF_8;

    private final Path filePath;

    public AtomicFileBackend(final Path filePath) {
        this.filePath = filePath;
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
            Files.write(tmp, data, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, filePath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                // Some network/union mounts cannot rename atomically; a plain replace is still safe
                // against truncation because a complete temp was written first.
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return new Fingerprint(filePath.toFile().lastModified(), data.length);
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
