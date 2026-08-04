package br.com.finalcraft.everyconfig.config.stress;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.jackson.JsoncCodec;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Stress (JSONC)")
class JsoncStressTest extends AbstractStressTest {
    @Override
    protected Codec newCodec() {
        return new JsoncCodec();
    }

    @Override
    protected String fileExtension() {
        return "jsonc";
    }
}
