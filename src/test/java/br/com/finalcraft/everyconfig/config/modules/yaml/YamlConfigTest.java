package br.com.finalcraft.everyconfig.config.modules.yaml;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.jackson.YamlCodec;
import br.com.finalcraft.everyconfig.config.modules.AbstractConfigTest;
import org.junit.jupiter.api.DisplayName;

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
    protected String malformedText() {
        return "a: [1, 2\nb: : :";
    }
}
