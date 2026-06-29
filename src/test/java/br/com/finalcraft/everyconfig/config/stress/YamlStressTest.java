package br.com.finalcraft.everyconfig.config.stress;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.jackson.YamlCodec;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Stress (YAML)")
class YamlStressTest extends AbstractStressTest {
    @Override
    protected Codec newCodec() {
        return new YamlCodec();
    }

    @Override
    protected String fileExtension() {
        return "yaml";
    }
}
