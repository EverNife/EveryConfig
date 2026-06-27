package br.com.finalcraft.everyconfig.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watches a file for external changes by polling its (mtime, size) on a daemon thread. Polling — rather
 * than a filesystem notification service — gives predictable, tunable latency on every platform.
 *
 * <p>It does not react to a {@code Config}'s own save: after a save, the owner calls
 * {@link #refreshSnapshot(Backend.Fingerprint)} with the exact bytes it wrote, and the watcher adopts
 * that baseline only if disk still matches it — so a foreign write that raced the save is still caught on
 * the next poll instead of being silently swallowed.
 */
final class FilePollWatcher implements Backend.Watcher {

    private static final Logger LOG = Logger.getLogger(FilePollWatcher.class.getName());

    private final Path filePath;
    private final long pollMillis;
    private final Runnable onChange;
    private final Thread thread;

    private volatile boolean running = true;
    private volatile Backend.Fingerprint baseline;

    FilePollWatcher(final Path filePath, final Duration pollInterval, final Runnable onChange) {
        this.filePath = filePath;
        this.pollMillis = Math.max(1L, pollInterval.toMillis());
        this.onChange = onChange;
        this.baseline = probe();
        this.thread = new Thread(this::run, "everyconfig-watcher-" + filePath.getFileName());
        this.thread.setDaemon(true);
    }

    @Override
    public void start() {
        thread.start();
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }

    @Override
    public void refreshSnapshot(final Backend.Fingerprint justWrote) {
        final Backend.Fingerprint now = probe();
        if (now.equals(justWrote)) {
            this.baseline = justWrote; // disk is exactly our write -> ignore it next poll
        }
        // else a foreign writer intervened: keep the old baseline so the next poll reloads its content.
    }

    private void run() {
        while (running) {
            try {
                Thread.sleep(pollMillis);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running) {
                return;
            }
            try {
                final Backend.Fingerprint now = probe();
                if (!now.equals(baseline) && now.size >= 0) {
                    this.baseline = now; // adopt before the callback so a save inside it is ignored
                    onChange.run();
                }
            } catch (final Exception e) {
                // A half-written file must not kill the thread; keep watching.
                LOG.log(Level.WARNING, "Auto-reload poll of '" + filePath + "' failed; still watching", e);
            }
        }
    }

    private Backend.Fingerprint probe() {
        try {
            if (Files.exists(filePath)) {
                return new Backend.Fingerprint(Files.getLastModifiedTime(filePath).toMillis(),
                        Files.size(filePath));
            }
        } catch (final IOException ignored) {
            // best-effort; treat as absent
        }
        return Backend.Fingerprint.ABSENT;
    }
}
