package br.com.finalcraft.everyconfig.config;

import br.com.finalcraft.everyconfig.codec.jackson.YamlCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code hasBeenModified()} against edits that leave the file's (mtime, size) unchanged. */
class ConfigHasBeenModifiedTest {

    @TempDir
    Path dir;

    @Test
    void aSameSizeEditInTheSameMtimeTickIsSeen() {
        final Path path = dir.resolve("localization.yml");
        final Config plugin = Config.open(path, new YamlCodec());
        plugin.setValue("fileName", "lang_EN_US.yml");
        plugin.save();
        final File file = path.toFile();
        final long stamp = file.lastModified();

        final Config editor = Config.open(path, new YamlCodec());
        editor.setValue("fileName", "lang_PT_BR.yml"); // same byte length as EN_US
        editor.save();
        assertTrue(file.setLastModified(stamp)); // the second save landed in the same clock tick

        assertTrue(plugin.hasBeenModified());
        plugin.reload();
        assertEquals("lang_PT_BR.yml", plugin.getString("fileName"));
        assertFalse(plugin.hasBeenModified());
    }

    @Test
    void anUntouchedFileIsNotModifiedRightAfterASave() {
        final Config c = Config.open(dir.resolve("c.yml"), new YamlCodec());
        c.setValue("v", "a");
        c.save();
        assertFalse(c.hasBeenModified()); // the stamp is fresh, so this compares content and finds it equal
    }

    @Test
    void aStampOlderThanTheLoadIsTrustedWithoutReadingTheFile() throws IOException {
        final Path path = dir.resolve("old.yml");
        Files.write(path, "v: a\n".getBytes(StandardCharsets.UTF_8));
        final File file = path.toFile();
        final long anHourAgo = System.currentTimeMillis() - 3_600_000L;
        assertTrue(file.setLastModified(anHourAgo));
        final Config c = Config.open(path, new YamlCodec());

        // Forging the old stamp back over a same-size edit is invisible ONLY because the content is not read:
        // a stamp a full tick older than the load cannot be shared by any later edit.
        Files.write(path, "v: b\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setLastModified(anHourAgo));
        assertFalse(c.hasBeenModified());
    }
}
