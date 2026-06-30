package br.com.finalcraft.everyconfig.binding;

import br.com.finalcraft.everyconfig.binding.merge.SmartMerge;
import br.com.finalcraft.everyconfig.binding.schema.Schema;
import br.com.finalcraft.everyconfig.binding.schema.SchemaCache;
import br.com.finalcraft.everyconfig.codec.jackson.JsonCodec;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.data.Dtos;
import br.com.finalcraft.everyconfig.core.comment.CommentTree;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WRITE-path merge internals the cross-codec contract cannot reach at the Config façade: the
 * {@link SmartMerge} primitive must keep a polymorphic type discriminator the candidate omits even under
 * REMOVE, and obsolete pruning must never fire inside an open (free-form) map. The observable merge
 * behaviors (unknown keys survive, the POJO owns its fields, the comment seed/override modes) live in the
 * codec-agnostic contract.
 */
class EntityBinderWriteTest {

    static class WithMap {
        public Map<String, Integer> limits = new LinkedHashMap<>();
    }

    private final JsonCodec json = new JsonCodec();

    private Config configFrom(final String src) {
        return new Config((ObjectNode) json.readTree(src));
    }

    @Test
    void removePolicyKeepsPolymorphicDiscriminator() {
        // The merge seam: a candidate whose polymorphic node omits the type discriminator must not let
        // REMOVE prune the discriminator the file already holds — a node missing its type tag fails to
        // deserialize on the next read.
        final ObjectNode canonical = (ObjectNode) json.readTree(
                "{\"shape\":{\"type\":\"circle\",\"radius\":2.5},\"label\":\"shapes\"}");
        final ObjectNode candidate = (ObjectNode) json.readTree(
                "{\"shape\":{\"radius\":2.5},\"label\":\"shapes\"}");
        final SchemaCache cache = new SchemaCache(json.getObjectMapper());
        final Schema schema = cache.of(json.getObjectMapper().constructType(Dtos.PolymorphicPojo.class));

        SmartMerge.mergeInto(canonical, candidate, schema,
                BindOptions.defaults().withObsoletePolicy(BindOptions.ObsoletePolicy.REMOVE),
                new CommentTree(), "", false);

        assertTrue(canonical.get("shape").has("type"), "type discriminator must survive REMOVE pruning");
        assertEquals("circle", canonical.get("shape").get("type").asText());
    }

    @Test
    void obsoletePruningNeverFiresInsideAMap() {
        final Config c = configFrom("{\"limits\":{\"a\":1,\"b\":2}}");
        final WithMap w = new WithMap();
        w.limits.put("a", 9);
        // Even under REMOVE, 'b' is a user map entry under an OPEN node and must survive.
        c.bind(WithMap.class, json,
                BindOptions.defaults().withObsoletePolicy(BindOptions.ObsoletePolicy.REMOVE))
                .write("", w);

        assertEquals(9, c.getInt("limits.a")); // overwritten by the POJO
        assertTrue(c.contains("limits.b"));    // preserved: the map is open
        assertEquals(2, c.getInt("limits.b"));
    }
}
