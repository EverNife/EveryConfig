package br.com.finalcraft.everyconfig.config.stress;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.jackson.TomlCodec;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Stress (TOML)")
class TomlStressTest extends AbstractStressTest {
    @Override
    protected Codec newCodec() {
        return new TomlCodec();
    }

    @Override
    protected String fileExtension() {
        return "toml";
    }
}
