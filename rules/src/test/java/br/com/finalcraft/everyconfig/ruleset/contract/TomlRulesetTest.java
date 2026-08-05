package br.com.finalcraft.everyconfig.ruleset.contract;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.jackson.TomlCodec;
import org.junit.jupiter.api.DisplayName;

/** The standard rule vocabulary over the TOML codec, whose tables and native dates are the sharpest test of a path and of a bound. */
@DisplayName("Ruleset (toml)")
class TomlRulesetTest extends AbstractRulesetTest {

    @Override
    protected Codec newCodec() {
        return new TomlCodec();
    }

    @Override
    protected String fileExtension() {
        return "toml";
    }

    @Override
    protected CommentFidelity fidelity() {
        return CommentFidelity.LOSSLESS;
    }

    /** TOML has no null type: a null-valued key is omitted on write and reads back absent, so the explicit
     *  null case cannot be expressed here. */
    @Override
    protected boolean supportsNull() {
        return false;
    }
}
