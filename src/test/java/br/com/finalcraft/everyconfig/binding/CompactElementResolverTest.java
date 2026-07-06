package br.com.finalcraft.everyconfig.binding;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.jackson.YamlCodec;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.data.Dtos;
import br.com.finalcraft.everyconfig.selfdescribe.CompactElementCodec;
import br.com.finalcraft.everyconfig.selfdescribe.CompactElementResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The consumer-supplied compact form: an UN-annotated type gets a compact-in-list codec purely from a
 * {@link CompactElementResolver} attached to the codec — the path a framework uses to teach EveryConfig a
 * third-party type it cannot annotate. No global registry is involved; the resolver lives on the codec.
 */
class CompactElementResolverTest {

    /** A compact "x,y" form for the un-annotated {@link Dtos.PlainPos}. */
    private static final CompactElementCodec<Dtos.PlainPos> PLAIN_POS_CODEC =
            new CompactElementCodec<Dtos.PlainPos>() {
                @Override
                public String encode(final Dtos.PlainPos p) {
                    return p.x + "," + p.y;
                }

                @Override
                public Dtos.PlainPos decode(final String s) {
                    final String[] parts = s.split(",");
                    return new Dtos.PlainPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
                }
            };

    /** Resolves a compact form ONLY for PlainPos — every other type binds normally. */
    private static final CompactElementResolver RESOLVER =
            type -> type == Dtos.PlainPos.class ? PLAIN_POS_CODEC : null;

    private static Codec codecWithResolver() {
        return new YamlCodec(new YamlCodec().getObjectMapper(), RESOLVER);
    }

    @Test
    @DisplayName("a consumer resolver makes an un-annotated type compact in a list, rich when solo")
    void consumerResolver_compactInListOnly(@TempDir final Path dir) {
        final Codec codec = codecWithResolver();
        final Path file = dir.resolve("pos.yml");

        final Config c = Config.open(file, codec);
        c.setValue("home", new Dtos.PlainPos(1, 2));                                          // solo -> rich object
        c.setValue("spots", Arrays.asList(new Dtos.PlainPos(3, 4), new Dtos.PlainPos(5, 6))); // list -> compact
        c.save();

        final Config r = Config.open(file, codec);
        assertTrue(r.getNode("home").isObject(), "solo should stay a rich object, got " + r.getNode("home"));
        assertEquals(1, r.getInt("home.x"));
        assertEquals(Arrays.asList("3,4", "5,6"), r.getStringList("spots"));      // the list is compact strings
        assertEquals(Arrays.asList(new Dtos.PlainPos(3, 4), new Dtos.PlainPos(5, 6)),
                r.getList("spots", Dtos.PlainPos.class));                         // and reads straight back
    }

    @Test
    @DisplayName("getList via a consumer resolver is tolerant of a compact string AND a rich object element")
    void consumerResolver_getListTolerant(@TempDir final Path dir) {
        final Map<String, Object> rich = new LinkedHashMap<>();
        rich.put("x", 1);
        rich.put("y", 2);

        final Config c = Config.open(dir.resolve("mix.yml"), codecWithResolver());
        c.setValue("mix", Arrays.asList("3,4", rich)); // one compact string, one rich object, in one array

        final List<Dtos.PlainPos> back = c.getList("mix", Dtos.PlainPos.class);
        assertEquals(2, back.size());
        assertEquals(new Dtos.PlainPos(3, 4), back.get(0)); // string -> the resolver's decode
        assertEquals(new Dtos.PlainPos(1, 2), back.get(1)); // object -> the rich bind
    }
}
