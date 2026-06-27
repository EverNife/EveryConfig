package br.com.finalcraft.everyconfig.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFileBackendTest {

    @TempDir
    Path dir;

    private byte[] bytes(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void writeAtomicThenReadBack() throws IOException {
        final AtomicFileBackend backend = new AtomicFileBackend(dir.resolve("a.yml"));
        assertFalse(backend.exists());
        assertNull(backend.readBytes());

        final Backend.Fingerprint fp = backend.writeAtomic(bytes("hello: world\n"));
        assertTrue(backend.exists());
        assertArrayEquals(bytes("hello: world\n"), backend.readBytes());
        assertEquals(bytes("hello: world\n").length, fp.size);
    }

    @Test
    void writeCreatesMissingParentDirectories() throws IOException {
        final AtomicFileBackend backend = new AtomicFileBackend(dir.resolve("nested/deep/b.yml"));
        backend.writeAtomic(bytes("x: 1\n"));
        assertTrue(backend.exists());
        assertArrayEquals(bytes("x: 1\n"), backend.readBytes());
    }

    @Test
    void backupUnparseableCopiesAsideAndIsNoopWhenAbsent() throws IOException {
        final Path target = dir.resolve("c.yml");
        final AtomicFileBackend backend = new AtomicFileBackend(target);
        assertNull(backend.backupUnparseable()); // nothing to back up

        backend.writeAtomic(bytes("corrupt: [\n"));
        final String bak = backend.backupUnparseable();
        assertTrue(bak.endsWith(".bak"));
        assertArrayEquals(bytes("corrupt: [\n"), Files.readAllBytes(Paths.get(bak)));
    }

    @Test
    void fsyncDurabilityWritesAndReadsBack() throws IOException {
        // FSYNC cannot be observed for its power-loss guarantee in a unit test; assert it is at least a
        // correct no-op for the visible round-trip (the bytes land and read back exactly).
        final AtomicFileBackend backend =
                new AtomicFileBackend(dir.resolve("fsync.yml"), Backend.Durability.FSYNC);
        final Backend.Fingerprint fp = backend.writeAtomic(bytes("durable: true\n"));
        assertTrue(backend.exists());
        assertArrayEquals(bytes("durable: true\n"), backend.readBytes());
        assertEquals(bytes("durable: true\n").length, fp.size);

        // Overwrite via FSYNC too, to exercise the replace-existing path under the forced mode.
        backend.writeAtomic(bytes("durable: true\nmore: 1\n"));
        assertArrayEquals(bytes("durable: true\nmore: 1\n"), backend.readBytes());
    }

    @Test
    void defaultDurabilityIsOsCacheAndRoundTrips() throws IOException {
        final AtomicFileBackend explicit =
                new AtomicFileBackend(dir.resolve("oscache.yml"), Backend.Durability.OS_CACHE);
        explicit.writeAtomic(bytes("k: v\n"));
        assertArrayEquals(bytes("k: v\n"), explicit.readBytes());
    }

    @Test
    void fingerprintReflectsContentChange() throws IOException {
        final AtomicFileBackend backend = new AtomicFileBackend(dir.resolve("d.yml"));
        backend.writeAtomic(bytes("a: 1\n"));
        final Backend.Fingerprint first = backend.fingerprint();
        backend.writeAtomic(bytes("a: 1\nb: 2\n"));
        final Backend.Fingerprint second = backend.fingerprint();
        assertFalse(first.equals(second));
    }

    @Test
    void statOnlyFingerprintMatchesHashedOneOnMtimeAndSize() {
        // The cheap (mtime,size) fingerprint behind hasBeenModified must stay equal to a hashed fingerprint
        // with the same mtime/size, so adding a content hash does not change hasBeenModified semantics.
        final Backend.Fingerprint stat = new Backend.Fingerprint(5L, 10L);
        final Backend.Fingerprint hashed = new Backend.Fingerprint(5L, 10L, 999L);
        assertEquals(stat, hashed);
        assertEquals(hashed, stat);
        assertEquals(stat.hashCode(), hashed.hashCode());
    }

    @Test
    void contentHashDistinguishesSameMtimeAndSize() {
        final Backend.Fingerprint a = new Backend.Fingerprint(5L, 10L, 111L);
        final Backend.Fingerprint b = new Backend.Fingerprint(5L, 10L, 222L);
        assertNotEquals(a, b); // identical (mtime,size) but different content -> distinct when both hashed
    }

    @Test
    void writeAtomicFingerprintCarriesContentHash() throws IOException {
        final AtomicFileBackend backend = new AtomicFileBackend(dir.resolve("hashed.yml"));
        final Backend.Fingerprint fp = backend.writeAtomic(bytes("x: 1\n"));
        assertNotEquals(Backend.Fingerprint.NO_HASH, fp.hash);
    }

    @Test
    void contentHashWatcherFiresOnSameSizeSameMtimeEdit() throws Exception {
        final Path file = dir.resolve("watched.yml");
        final AtomicFileBackend backend = new AtomicFileBackend(file);
        backend.writeAtomic(bytes("value: 1\n"));
        final FileTime original = Files.getLastModifiedTime(file);

        final CountDownLatch fired = new CountDownLatch(1);
        final Backend.Watcher watcher =
                backend.watch(Duration.ofMillis(20), fired::countDown, true);
        watcher.start();
        try {
            // Same byte length, different content, and the SAME mtime (a coarse-tick collision): a stat-only
            // watcher would miss this; the content-hash watcher catches it.
            Files.write(file, bytes("value: 2\n"));
            Files.setLastModifiedTime(file, original);
            assertTrue(fired.await(2, TimeUnit.SECONDS),
                    "content-hash watcher should fire on a same-size, same-mtime edit");
        } finally {
            watcher.close();
        }
    }
}
