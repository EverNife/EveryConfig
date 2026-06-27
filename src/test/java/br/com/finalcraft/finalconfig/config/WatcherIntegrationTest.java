package br.com.finalcraft.finalconfig.config;

import br.com.finalcraft.finalconfig.codec.jackson.YamlCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatcherIntegrationTest {

    @TempDir
    Path dir;

    private final YamlCodec yaml = new YamlCodec();

    private static boolean waitUntil(final BooleanSupplier cond, final long timeoutMs) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20L);
        }
        return cond.getAsBoolean();
    }

    @Test
    void externalEditTriggersReloadAndCallback() throws IOException, InterruptedException {
        final Path file = dir.resolve("live.yml");
        final Config c = Config.open(file, yaml);
        c.setValue("a", 1);
        c.save();

        final AtomicInteger reloads = new AtomicInteger();
        c.onReload(reloads::incrementAndGet).withAutoReload(Duration.ofMillis(40));
        try {
            // A differently-sized external edit, so the (mtime,size) fingerprint reliably differs.
            Files.write(file, "a: 12345\n".getBytes(StandardCharsets.UTF_8));

            assertTrue(waitUntil(() -> c.getInt("a", 0) == 12345, 10000L),
                    "watcher should have reloaded the external edit");
            assertEquals(12345, c.getInt("a"));
            assertTrue(reloads.get() >= 1, "onReload callback should have fired");
        } finally {
            c.close();
        }
    }
}
