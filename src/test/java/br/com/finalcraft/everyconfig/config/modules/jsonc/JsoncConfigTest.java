package br.com.finalcraft.everyconfig.config.modules.jsonc;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.jackson.JsoncCodec;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.modules.AbstractConfigTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config contract over the JSON-with-comments codec (fidelity LOSSY: block/side/header comments above
 * a key round-trip, and a scalar list's per-element comments round-trip via the {@code list.i} grammar;
 * positions the path-keyed overlay cannot address are not preserved).
 */
@DisplayName("JsoncConfig (fidelity=LOSSY, comment-aware)")
class JsoncConfigTest extends AbstractConfigTest {

    @Override
    protected Codec newCodec() {
        return new JsoncCodec();
    }

    @Override
    protected String fileExtension() {
        return "jsonc";
    }

    @Override
    protected CommentFidelity fidelity() {
        return CommentFidelity.LOSSY;
    }

    @Override
    protected boolean supportsListItemComments() {
        return true; // a scalar list renders multi-line so each element's comment has an addressable home
    }

    @Test
    @Order(310)
    @DisplayName("[jsonc] a per-element comment is emitted immediately above its list item")
    void listItemComment_emittedAboveElement() throws IOException {
        final Config c = open();
        c.setValue("tags", Arrays.asList("alpha", "beta"));
        c.setComment("tags.0", "the primary tag");
        c.save();

        final String text = readText();
        assertTrue(text.contains("// the primary tag\n    \"alpha\""),
                "the element comment should sit directly above its item:\n" + text);
    }

    @Test
    @Order(311)
    @DisplayName("[jsonc] the emitted layout matches the golden fixture byte-for-byte")
    void goldenLayout_byteStable() throws IOException {
        assertGoldenLayout();
    }
}
