package br.com.finalcraft.everyconfig.ruleset.contract;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.jackson.JsonCodec;
import org.junit.jupiter.api.DisplayName;

/** The standard rule vocabulary over the JSON codec, where nothing is documented in the file but every rule still fires. */
@DisplayName("Ruleset (json)")
class JsonRulesetTest extends AbstractRulesetTest {

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
}
