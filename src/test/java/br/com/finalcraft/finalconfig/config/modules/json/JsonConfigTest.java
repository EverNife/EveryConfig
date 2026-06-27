package br.com.finalcraft.finalconfig.config.modules.json;

import br.com.finalcraft.finalconfig.codec.Codec;
import br.com.finalcraft.finalconfig.codec.CommentFidelity;
import br.com.finalcraft.finalconfig.codec.jackson.JsonCodec;
import br.com.finalcraft.finalconfig.config.modules.AbstractConfigTest;
import org.junit.jupiter.api.DisplayName;

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
}
