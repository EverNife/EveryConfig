package br.com.finalcraft.everyconfig.config.modules.json;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.jackson.JsonCodec;
import br.com.finalcraft.everyconfig.config.modules.AbstractConfigTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/** The config contract over the JSON codec (fidelity NONE: comment tests are skipped, data must survive). */
@DisplayName("JsonConfig (fidelity=NONE, comments stripped)")
class JsonConfigTest extends AbstractConfigTest {

    @Override
    protected Codec newCodec() {
        return new JsonCodec();
    }

    @Override
    protected String fileExtension() {
        return "json";
    }

    @Override
    protected CommentFidelity fidelity() {
        return CommentFidelity.NONE;
    }

    @Override
    protected String malformedText() {
        return "{ \"a\": ";
    }

    /** JSON is emitted by the mapper's plain writer, not a structure emitter, so key-ordering pins are not
     *  honored (the output follows live-tree order). */
    @Override
    protected boolean supportsKeyOrdering() {
        return false;
    }

    @Test
    @Order(320)
    @DisplayName("[json] the emitted layout matches the golden fixture byte-for-byte (no comments)")
    void goldenLayout_byteStable() throws IOException {
        assertGoldenLayout();
    }
}
