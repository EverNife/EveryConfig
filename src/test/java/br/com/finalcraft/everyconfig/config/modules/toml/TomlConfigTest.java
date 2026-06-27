package br.com.finalcraft.everyconfig.config.modules.toml;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.jackson.TomlCodec;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.data.Dtos;
import br.com.finalcraft.everyconfig.config.modules.AbstractConfigTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config contract over the TOML codec. TOML has no explicit null, so the null / empty-vs-null tests
 * are skipped via {@link #supportsNull()}. Adds TOML-specific assertions on the on-disk representation
 * that only make sense for this format.
 */
@DisplayName("TomlConfig")
class TomlConfigTest extends AbstractConfigTest {

    @Override
    protected Codec newCodec() {
        return new TomlCodec();
    }

    @Override
    protected String fileExtension() {
        return "toml";
    }

    @Override
    protected CommentFidelity fidelity() {
        return CommentFidelity.LOSSLESS;
    }

    @Override
    protected boolean supportsNull() {
        // TOML has no null type: a null-valued key is omitted on write, so it reads back absent (the
        // collapse rule pinned by tomlNull_collapsesToAbsenceOnDisk). The abstract null tests assert the
        // opposite (preservation), so they stay skipped here.
        return false;
    }

    /**
     * TOML's null contract: a null collapses to absence. In memory {@code setValue(path, null)} already
     * removes the key (every codec), while an explicit null put straight into the tree survives in memory
     * but is dropped on write — so after a disk round-trip the key is absent and a typed read returns the
     * supplied default. A real-valued sibling proves only the null collapses.
     */
    @Test
    @Order(201)
    @DisplayName("[toml] an explicit null collapses to absence on a disk round-trip")
    void tomlNull_collapsesToAbsenceOnDisk() {
        final Config c = open();
        c.setValue("a", 1);
        c.setValue("z", null);            // collapses in memory for any codec
        assertFalse(c.contains("z"));
        c.getRoot().putNull("n");          // an explicit null that bypasses the setValue collapse
        assertTrue(c.contains("n"));
        c.save();

        final Config r = open();
        assertFalse(r.contains("n"));      // the null key is gone on disk (omitted by the emitter)
        assertEquals(7, r.getInt("n", 7)); // a typed read of the absent key returns the supplied default
        assertEquals(1, r.getInt("a"));    // the real-valued sibling survived
    }

    /**
     * The TOML reader drops the high digits of a maximal {@code long} (it mis-parses
     * {@code 9223372036854775807}), so the codec stores such an integer as a quoted string. This pins both
     * that the workaround actually fires at {@code Long.MAX_VALUE} (the on-disk form is quoted, not a bare
     * integer the reader would mangle) and that the value still round-trips losslessly.
     */
    @Test
    @Order(200)
    @DisplayName("[toml] Long.MAX_VALUE is stored as a quoted string and round-trips losslessly")
    void longMaxValue_storedAsQuotedString_roundTrips() throws IOException {
        final Config c = open();
        c.setValue("k", Long.MAX_VALUE);
        c.save();

        final String text = readText();
        final boolean quoted = text.contains("'9223372036854775807'")
                || text.contains("\"9223372036854775807\"");
        assertTrue(quoted, "Long.MAX_VALUE should be emitted as a quoted string");
        assertFalse(text.contains("= 9223372036854775807"),
                "Long.MAX_VALUE should NOT be emitted as a bare integer the TOML reader would mangle");

        final Config r = open();
        assertEquals(Long.MAX_VALUE, r.getLong("k"));
        assertEquals("9223372036854775807", r.getString("k"));
    }

    /**
     * A list of non-empty objects is written in TOML's idiomatic {@code [[array-of-tables]]} form (repeated
     * {@code [[path]]} blocks), not a single-line inline array of inline tables.
     */
    @Test
    @Order(202)
    @DisplayName("[toml] a list of POJOs is emitted as [[array-of-tables]]")
    void listOfPojos_emittedAsArrayOfTables() throws IOException {
        final Dtos.ListOfPojoPojo.Server s1 = new Dtos.ListOfPojoPojo.Server();
        s1.name = "alpha";
        s1.port = 1;
        final Dtos.ListOfPojoPojo.Server s2 = new Dtos.ListOfPojoPojo.Server();
        s2.name = "beta";
        s2.port = 2;
        final Dtos.ListOfPojoPojo p = new Dtos.ListOfPojoPojo();
        p.title = "cluster";
        p.servers = Arrays.asList(s1, s2);

        final Config c = open();
        c.setValue("cfg", p);
        c.save();

        final String text = readText();
        final int first = text.indexOf("[[cfg.servers]]");
        assertTrue(first >= 0, "expected idiomatic [[cfg.servers]] blocks");
        assertTrue(text.indexOf("[[cfg.servers]]", first + 1) > first,
                "expected one [[cfg.servers]] per element");
        assertFalse(text.contains("servers = ["), "should not fall back to an inline array of inline tables");
    }

    /** Scalar arrays and an empty object-list keep the inline form ([[path]] can't express an empty body). */
    @Test
    @Order(203)
    @DisplayName("[toml] scalar arrays and empty lists stay inline")
    void scalarAndEmptyArrays_stayInline() throws IOException {
        final Config c = open();
        c.setValue("nums", Arrays.asList(1, 2, 3));
        c.setValue("empty", Arrays.asList());
        c.save();

        final String text = readText();
        assertTrue(text.contains("nums = ["), "a scalar array stays an inline array");
        assertFalse(text.contains("[[nums]]"), "a scalar array must not become an array-of-tables");
        assertFalse(text.contains("[[empty]]"), "an empty list must not become an array-of-tables");

        final Config r = open();
        assertEquals(3, r.getList("nums").size());
        assertEquals(0, r.getList("empty").size());
    }
}
