package br.com.finalcraft.everyconfig.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void fingerprintReflectsContentChange() throws IOException {
        final AtomicFileBackend backend = new AtomicFileBackend(dir.resolve("d.yml"));
        backend.writeAtomic(bytes("a: 1\n"));
        final Backend.Fingerprint first = backend.fingerprint();
        backend.writeAtomic(bytes("a: 1\nb: 2\n"));
        final Backend.Fingerprint second = backend.fingerprint();
        assertFalse(first.equals(second));
    }
}
