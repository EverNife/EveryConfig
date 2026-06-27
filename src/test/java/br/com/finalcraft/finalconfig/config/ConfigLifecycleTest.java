package br.com.finalcraft.finalconfig.config;

import br.com.finalcraft.finalconfig.codec.jackson.YamlCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLifecycleTest {

    @TempDir
    Path dir;

    private final YamlCodec yaml = new YamlCodec();

    @Test
    void saveThenReopenRoundTripsDataAndComments() {
        final Path file = dir.resolve("server.yml");
        final Config c = Config.open(file, yaml);
        c.setValue("server.host", "localhost");
        c.getOrSetDefaultValue("server.port", 8080, "the listen port");
        c.save();

        // A brand-new handle over the same file recovers data AND the seeded comment from disk.
        final Config reopened = Config.open(file, yaml);
        assertEquals("localhost", reopened.getString("server.host"));
        assertEquals(8080, reopened.getInt("server.port"));
        assertEquals("the listen port", reopened.getComment("server.port"));
    }

    @Test
    void openAbsentFileGivesEmptyTreeAndFirstSaveCreatesIt() {
        final Path file = dir.resolve("new.yml");
        final Config c = Config.open(file, yaml);
        assertEquals(LoadStatus.ABSENT, c.lastLoadStatus());
        assertFalse(Files.exists(file));
        c.setValue("x", 1);
        c.save();
        assertTrue(Files.exists(file));
    }

    @Test
    void saveIfDirtyOnlyWritesAfterAMutation() {
        final Path file = dir.resolve("dirty.yml");
        final Config c = Config.open(file, yaml);
        c.saveIfDirty();                 // nothing changed yet
        assertFalse(Files.exists(file)); // so no file was created
        c.setValue("a", 1);
        c.saveIfDirty();
        assertTrue(Files.exists(file));
    }

    @Test
    void unparseableFileIsBackedUpAndStartsEmpty() throws IOException {
        final Path file = dir.resolve("broken.yml");
        Files.write(file, "a: [1, 2\n".getBytes(StandardCharsets.UTF_8)); // unclosed flow sequence

        final Config c = Config.open(file, yaml);
        assertEquals(LoadStatus.PARSE_FAILED_BACKED_UP, c.lastLoadStatus());
        assertTrue(Files.exists(dir.resolve("broken.yml.bak")));
        assertTrue(c.getKeys().isEmpty());
    }

    @Test
    void reloadPicksUpAnExternalEdit() throws IOException {
        final Path file = dir.resolve("watched.yml");
        final Config c = Config.open(file, yaml);
        c.setValue("a", 1);
        c.save();

        Files.write(file, "a: 2\n".getBytes(StandardCharsets.UTF_8)); // external change
        c.reload();
        assertEquals(2, c.getInt("a"));
        assertEquals(LoadStatus.OK, c.lastLoadStatus());
    }

    @Test
    void reloadKeepsTreeAndFlagsDivergenceOnParseFailure() throws IOException {
        final Path file = dir.resolve("transient.yml");
        final Config c = Config.open(file, yaml);
        c.setValue("a", 1);
        c.save();

        Files.write(file, "a: [oops\n".getBytes(StandardCharsets.UTF_8)); // half-written / corrupt
        c.reload();
        assertEquals(1, c.getInt("a"));                 // live tree kept
        assertEquals(LoadStatus.PARSE_FAILED_KEPT, c.lastLoadStatus());
        assertTrue(c.isDivergedFromDisk());
    }
}
