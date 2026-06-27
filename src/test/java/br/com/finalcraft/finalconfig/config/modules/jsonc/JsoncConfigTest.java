package br.com.finalcraft.finalconfig.config.modules.jsonc;

import br.com.finalcraft.finalconfig.codec.Codec;
import br.com.finalcraft.finalconfig.codec.CommentFidelity;
import br.com.finalcraft.finalconfig.codec.jackson.JsoncCodec;
import br.com.finalcraft.finalconfig.config.modules.AbstractConfigTest;
import org.junit.jupiter.api.DisplayName;

/**
 * The config contract over the JSON-with-comments codec. TDD TARGET: {@code JsoncCodec}'s text engine is
 * not built yet, so every on-disk round-trip is expected to fail until it is. Declared LOSSY, so the
 * comment-fidelity tests run (and currently fail) rather than being skipped.
 */
@DisplayName("JsoncConfig (TDD target - codec text engine not implemented yet)")
class JsoncConfigTest extends AbstractConfigTest {

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
