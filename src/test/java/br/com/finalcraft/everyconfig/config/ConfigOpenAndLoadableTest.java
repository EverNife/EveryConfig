package br.com.finalcraft.everyconfig.config;

import br.com.finalcraft.everyconfig.codec.CodecException;
import br.com.finalcraft.everyconfig.codec.jackson.JsonCodec;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The filename-driven {@code open(...)} overloads (codec from extension, fail-fast) and {@code getLoadable}. */
class ConfigOpenAndLoadableTest {

    @TempDir
    Path dir;

    static class Db {
        public String url = "def";
        public int maxPool = 10;
    }

    @Test
    void openStringDerivesCodecFromExtensionAndRoundTrips() {
        final String path = dir.resolve("server.yml").toString();
        final Config w = Config.open(path); // derives a YamlCodec from ".yml"
        w.setValue("server.host", "localhost");
        w.setValue("server.port", 25565);
        w.save();

        final Config r = Config.open(path);
        assertEquals("localhost", r.getString("server.host"));
        assertEquals(25565, r.getInt("server.port")); // a number round-tripping proves a real codec, not text
    }

    @Test
    void openFileDerivesCodec() {
        final File file = dir.resolve("data.json").toFile();
        final Config w = Config.open(file);
        w.setValue("a.b", 7);
        w.save();
        assertEquals(7, Config.open(file).getInt("a.b"));
    }

    @Test
    void openUnknownExtensionThrows() {
        assertThrows(CodecException.class, () -> Config.open(dir.resolve("x.unknownext").toString()));
    }

    @Test
    void openMissingExtensionThrows() {
        assertThrows(CodecException.class, () -> Config.open(dir.resolve("noextension").toString()));
    }

    @Test
    void getLoadableBindsSubtreeWithLifecycleCodec() {
        final Config c = Config.open(dir.resolve("c.yml").toString());
        c.setValue("db.url", "jdbc:x");
        c.setValue("db.maxPool", 25);

        final Db db = c.getLoadable("db", Db.class);
        assertEquals("jdbc:x", db.url);
        assertEquals(25, db.maxPool);
    }

    @Test
    void getLoadableWithoutCodecThrows() {
        assertThrows(IllegalStateException.class, () -> new Config().getLoadable("db", Db.class));
    }

    @Test
    void getLoadableExplicitCodec() {
        final JsonCodec json = new JsonCodec();
        final Config c = new Config((ObjectNode) json.readTree("{\"db\":{\"url\":\"u\",\"maxPool\":5}}"));
        final Db db = c.getLoadable("db", Db.class, json);
        assertEquals("u", db.url);
        assertEquals(5, db.maxPool);
    }
}
