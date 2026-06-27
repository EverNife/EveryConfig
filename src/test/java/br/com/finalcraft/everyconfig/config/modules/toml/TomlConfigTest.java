package br.com.finalcraft.everyconfig.config.modules.toml;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.jackson.TomlCodec;
import br.com.finalcraft.everyconfig.config.modules.AbstractConfigTest;
import org.junit.jupiter.api.DisplayName;

/**
 * The config contract over the TOML codec. TDD TARGET: {@code TomlCodec}'s text engine is not built yet,
 * so every on-disk round-trip is expected to fail until it is. TOML has no explicit null, so the
 * null/empty-vs-null tests are skipped via {@link #supportsNull()}.
 */
@DisplayName("TomlConfig (TDD target - codec text engine not implemented yet)")
class TomlConfigTest extends AbstractConfigTest {

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

    @Override
    protected boolean supportsNull() {
        return false; // TOML has no null type
    }
}
