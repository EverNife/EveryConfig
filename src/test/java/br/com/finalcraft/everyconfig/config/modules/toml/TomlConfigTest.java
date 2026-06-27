package br.com.finalcraft.everyconfig.config.modules.toml;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.jackson.TomlCodec;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.modules.AbstractConfigTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.io.IOException;

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
}
