package br.com.finalcraft.everyconfig.config.modules.yaml;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.jackson.YamlCodec;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.modules.AbstractConfigTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The config contract over the YAML codec (fidelity LOSSLESS: the full comment-aware path runs). */
@DisplayName("YamlConfig (fidelity=LOSSLESS, comment-aware)")
class YamlConfigTest extends AbstractConfigTest {

    @Override
    protected Codec newCodec() {
        return new YamlCodec();
    }

    @Override
    protected String fileExtension() {
        return "yaml";
    }

    @Override
    protected CommentFidelity fidelity() {
        return CommentFidelity.LOSSLESS;
    }

    @Override
    protected boolean supportsListItemComments() {
        return true; // YAML is the codec that round-trips per-element scalar-list comments
    }

    @Override
    protected String malformedText() {
        return "a: [1, 2\nb: : :";
    }

    @Test
    @Order(300)
    @DisplayName("[yaml] a per-element comment is emitted immediately above its list item")
    void listItemComment_emittedAboveElement() throws IOException {
        final Config c = open();
        c.setValue("tags", Arrays.asList("alpha", "beta"));
        c.setComment("tags.0", "the primary tag");
        c.save();

        final String text = readText();
        assertTrue(text.contains("  # the primary tag\n  - alpha"),
                "the element comment should sit directly above its item:\n" + text);
    }

    @Test
    @Order(301)
    @DisplayName("[yaml] the emitted layout matches the golden fixture byte-for-byte")
    void goldenLayout_byteStable() throws IOException {
        assertGoldenLayout();
    }
}
