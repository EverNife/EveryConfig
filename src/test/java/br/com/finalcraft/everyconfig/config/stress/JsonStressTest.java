package br.com.finalcraft.everyconfig.config.stress;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.jackson.JsonCodec;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Stress (JSON)")
class JsonStressTest extends AbstractStressTest {
    @Override
    protected Codec newCodec() {
        return new JsonCodec();
    }

    @Override
    protected String fileExtension() {
        return "json";
    }
}
