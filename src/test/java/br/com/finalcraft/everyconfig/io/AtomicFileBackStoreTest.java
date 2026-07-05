package br.com.finalcraft.everyconfig.io;

import br.com.finalcraft.everyconfig.io.watcher.Watcher;
import br.com.finalcraft.everyconfig.io.watcher.Fingerprint;
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

class AtomicFileBackStoreTest {

    @TempDir
    Path dir;

    private byte[] bytes(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void writeAtomicThenReadBack() throws IOException {
        final AtomicFileBackStore backStore = new AtomicFileBackStore(dir.resolve("a.yml"));
        assertFalse(backStore.exists());
        assertNull(backStore.readBytes());

        final Fingerprint fp = backStore.writeAtomic(bytes("hello: world\n"));
        assertTrue(backStore.exists());
        assertArrayEquals(bytes("hello: world\n"), backStore.readBytes());
        assertEquals(bytes("hello: world\n").length, fp.size);
    }

    @Test
    void writeCreatesMissingParentDirectories() throws IOException {
        final AtomicFileBackStore backStore = new AtomicFileBackStore(dir.resolve("nested/deep/b.yml"));
        backStore.writeAtomic(bytes("x: 1\n"));
        assertTrue(backStore.exists());
        assertArrayEquals(bytes("x: 1\n"), backStore.readBytes());
    }

    @Test
    void backupUnparseableCopiesAsideAndIsNoopWhenAbsent() throws IOException {
        final Path target = dir.resolve("c.yml");
        final AtomicFileBackStore backStore = new AtomicFileBackStore(target);
        assertNull(backStore.backupUnparseable()); // nothing to back up

        backStore.writeAtomic(bytes("corrupt: [\n"));
        final String bak = backStore.backupUnparseable();
        assertTrue(bak.endsWith(".bak"));
        assertArrayEquals(bytes("corrupt: [\n"), Files.readAllBytes(Paths.get(bak)));
    }

    @Test
    void fsyncDurabilityWritesAndReadsBack() throws IOException {
        // FSYNC cannot be observed for its power-loss guarantee in a unit test; assert it is at least a
        // correct no-op for the visible round-trip (the bytes land and read back exactly).
        final AtomicFileBackStore backStore =
                new AtomicFileBackStore(dir.resolve("fsync.yml"), BackStore.Durability.FSYNC);
        final Fingerprint fp = backStore.writeAtomic(bytes("durable: true\n"));
        assertTrue(backStore.exists());
        assertArrayEquals(bytes("durable: true\n"), backStore.readBytes());
        assertEquals(bytes("durable: true\n").length, fp.size);

        // Overwrite via FSYNC too, to exercise the replace-existing path under the forced mode.
        backStore.writeAtomic(bytes("durable: true\nmore: 1\n"));
        assertArrayEquals(bytes("durable: true\nmore: 1\n"), backStore.readBytes());
    }

    @Test
    void defaultDurabilityIsOsCacheAndRoundTrips() throws IOException {
        final AtomicFileBackStore explicit =
                new AtomicFileBackStore(dir.resolve("oscache.yml"), BackStore.Durability.OS_CACHE);
        explicit.writeAtomic(bytes("k: v\n"));
        assertArrayEquals(bytes("k: v\n"), explicit.readBytes());
    }

    @Test
    void fingerprintReflectsContentChange() throws IOException {
        final AtomicFileBackStore backStore = new AtomicFileBackStore(dir.resolve("d.yml"));
        backStore.writeAtomic(bytes("a: 1\n"));
        final Fingerprint first = backStore.fingerprint();
        backStore.writeAtomic(bytes("a: 1\nb: 2\n"));
        final Fingerprint second = backStore.fingerprint();
        assertFalse(first.equals(second));
    }

    @Test
    void statOnlyFingerprintMatchesHashedOneOnMtimeAndSize() {
        // The cheap (mtime,size) fingerprint behind hasBeenModified must stay equal to a hashed fingerprint
        // with the same mtime/size, so adding a content hash does not change hasBeenModified semantics.
        final Fingerprint stat = new Fingerprint(5L, 10L);
        final Fingerprint hashed = new Fingerprint(5L, 10L, 999L);
        assertEquals(stat, hashed);
        assertEquals(hashed, stat);
        assertEquals(stat.hashCode(), hashed.hashCode());
    }

    @Test
    void contentHashDistinguishesSameMtimeAndSize() {
        final Fingerprint a = new Fingerprint(5L, 10L, 111L);
        final Fingerprint b = new Fingerprint(5L, 10L, 222L);
        assertNotEquals(a, b); // identical (mtime,size) but different content -> distinct when both hashed
    }

    @Test
    void writeAtomicFingerprintCarriesContentHash() throws IOException {
        final AtomicFileBackStore backStore = new AtomicFileBackStore(dir.resolve("hashed.yml"));
        final Fingerprint fp = backStore.writeAtomic(bytes("x: 1\n"));
        assertNotEquals(Fingerprint.NO_HASH, fp.hash);
    }

    @Test
    void contentHashWatcherFiresOnSameSizeSameMtimeEdit() throws Exception {
        final Path file = dir.resolve("watched.yml");
        final AtomicFileBackStore backStore = new AtomicFileBackStore(file);
        backStore.writeAtomic(bytes("value: 1\n"));
        final FileTime original = Files.getLastModifiedTime(file);

        final CountDownLatch fired = new CountDownLatch(1);
        final Watcher watcher =
                backStore.watch(Duration.ofMillis(20), fired::countDown, true);
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
