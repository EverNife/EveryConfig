package br.com.finalcraft.finalconfig.codec;

import br.com.finalcraft.finalconfig.codec.jackson.JsonCodec;
import br.com.finalcraft.finalconfig.codec.jackson.JsoncCodec;
import br.com.finalcraft.finalconfig.codec.jackson.TomlCodec;
import br.com.finalcraft.finalconfig.codec.jackson.YamlCodec;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves a {@link Codec} from a file extension. Selection is fail-fast and never content-sniffs: a
 * YAML document and a JSON document overlap, so guessing is ambiguous by construction. An unknown
 * extension raises a {@link CodecException} rather than defaulting to a format.
 *
 * <p>The built-in defaults register JSON, YAML, TOML and JSONC. Registering a codec maps each of its
 * declared extensions (lowercased) to it; the last registration for an extension wins.
 */
public final class CodecRegistry {

    private final Map<String, Codec> byExtension = new LinkedHashMap<>();

    /** A registry pre-loaded with the built-in JSON, YAML, TOML and JSONC codecs (default instances). */
    public static CodecRegistry defaults() {
        final CodecRegistry registry = new CodecRegistry();
        registry.register(new JsonCodec());
        registry.register(new YamlCodec());
        registry.register(new TomlCodec());
        registry.register(new JsoncCodec());
        return registry;
    }

    /** Maps each of the codec's declared extensions (lowercased) to it; last registration wins. */
    public CodecRegistry register(final Codec codec) {
        if (codec == null) {
            throw new IllegalArgumentException("codec cannot be null");
        }
        for (final String ext : codec.fileExtensions()) {
            byExtension.put(ext.toLowerCase(Locale.ROOT), codec);
        }
        return this;
    }

    /** Resolves by file extension (case-insensitive, no leading dot). Throws if unknown. */
    public Codec byExtension(final String ext) {
        if (ext == null) {
            throw new CodecException("no file extension given");
        }
        final Codec codec = byExtension.get(ext.toLowerCase(Locale.ROOT));
        if (codec == null) {
            throw new CodecException("no codec registered for extension: " + ext);
        }
        return codec;
    }

    /** Resolves from a file name by deriving its extension. Throws if missing or unknown. */
    public Codec forFile(final String fileName) {
        if (fileName == null) {
            throw new CodecException("no file name given");
        }
        final int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new CodecException("file has no extension to resolve a codec: " + fileName);
        }
        return byExtension(fileName.substring(dot + 1));
    }
}
