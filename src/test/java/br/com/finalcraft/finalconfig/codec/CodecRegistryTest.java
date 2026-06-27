package br.com.finalcraft.finalconfig.codec;

import br.com.finalcraft.finalconfig.codec.jackson.JsonCodec;
import br.com.finalcraft.finalconfig.codec.jackson.JsoncCodec;
import br.com.finalcraft.finalconfig.codec.jackson.TomlCodec;
import br.com.finalcraft.finalconfig.codec.jackson.YamlCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodecRegistryTest {

    @Test
    void forFileResolvesYaml() {
        assertTrue(CodecRegistry.defaults().forFile("a.yml") instanceof YamlCodec);
        assertTrue(CodecRegistry.defaults().forFile("a.yaml") instanceof YamlCodec);
    }

    @Test
    void forFileResolvesJson() {
        assertTrue(CodecRegistry.defaults().forFile("a.json") instanceof JsonCodec);
    }

    @Test
    void forFileResolvesTomlAndJsonc() {
        assertTrue(CodecRegistry.defaults().forFile("a.toml") instanceof TomlCodec);
        assertTrue(CodecRegistry.defaults().forFile("a.jsonc") instanceof JsoncCodec);
    }

    @Test
    void unknownExtensionThrows() {
        assertThrows(CodecException.class, () -> CodecRegistry.defaults().forFile("a.xyz"));
        assertThrows(CodecException.class, () -> CodecRegistry.defaults().byExtension("xyz"));
    }

    @Test
    void extensionResolutionIsCaseInsensitive() {
        assertTrue(CodecRegistry.defaults().byExtension("YML") instanceof YamlCodec);
        assertTrue(CodecRegistry.defaults().byExtension("JSON") instanceof JsonCodec);
    }

    @Test
    void fileWithoutExtensionThrows() {
        assertThrows(CodecException.class, () -> CodecRegistry.defaults().forFile("noext"));
    }
}
