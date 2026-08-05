package br.com.finalcraft.everyconfig.ruleset.contract;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.jackson.JsoncCodec;
import org.junit.jupiter.api.DisplayName;

/** The standard rule vocabulary over the JSONC codec. */
@DisplayName("Ruleset (jsonc)")
class JsoncRulesetTest extends AbstractRulesetTest {

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
}
