package br.com.finalcraft.everyconfig.ruleset.contract;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.jackson.YamlCodec;
import org.junit.jupiter.api.DisplayName;

/** The standard rule vocabulary over the YAML codec. */
@DisplayName("Ruleset (yaml)")
class YamlRulesetTest extends AbstractRulesetTest {

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
}
