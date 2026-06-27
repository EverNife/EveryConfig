package br.com.finalcraft.everyconfig.binding;

import br.com.finalcraft.everyconfig.annotation.Comment;
import br.com.finalcraft.everyconfig.annotation.CommentMode;
import br.com.finalcraft.everyconfig.annotation.Key;
import br.com.finalcraft.everyconfig.binding.merge.SmartMerge;
import br.com.finalcraft.everyconfig.binding.schema.Schema;
import br.com.finalcraft.everyconfig.binding.schema.SchemaCache;
import br.com.finalcraft.everyconfig.core.comment.CommentTree;
import br.com.finalcraft.everyconfig.codec.jackson.JsonCodec;
import br.com.finalcraft.everyconfig.codec.jackson.YamlCodec;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.data.Dtos;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WRITE path: a binding save MERGES into the tree (unknown keys survive), the POJO owns its declared
 *  fields, comments are seeded but never overwrite a user edit, and obsolete pruning is opt-in and never
 *  touches a free-form map. */
class EntityBinderWriteTest {

    @Comment("Connection settings")
    static class Db {
        @Comment("the JDBC url")
        @Key("jdbc-url")
        public String url = "jdbc:h2:mem:test";

        public int maxPool = 10;

        @Comment(value = "tune this", mode = CommentMode.SET_IF_ABSENT)
        public int retries = 3;
    }

    static class WithMap {
        public Map<String, Integer> limits = new LinkedHashMap<>();
    }

    private final JsonCodec json = new JsonCodec();
    private final YamlCodec yaml = new YamlCodec();

    private Config configFrom(final String src) {
        return new Config((ObjectNode) json.readTree(src));
    }

    @Test
    void mergeAddsMissingKeysAndPreservesUnknown() {
        final Config c = configFrom("{\"jdbc-url\":\"jdbc:user\",\"legacyKey\":\"keepme\"}");
        final Db db = new Db();
        db.url = "jdbc:user";
        c.mergeFrom(db, json);

        assertTrue(c.contains("legacyKey"));                 // unknown key survives the merge
        assertEquals("keepme", c.getString("legacyKey"));
        assertEquals(10, c.getInt("maxPool"));               // missing-from-file added from the POJO
        assertEquals("jdbc:user", c.getString("jdbc-url"));
    }

    @Test
    void pojoValueWinsForDeclaredField() {
        final Config c = configFrom("{\"maxPool\":5}");
        final Db db = new Db();
        db.maxPool = 10;
        c.mergeFrom(db, json);
        assertEquals(10, c.getInt("maxPool"));
    }

    @Test
    void commentSeededFromAnnotationOnFreshConfig() {
        final Config c = new Config();
        c.mergeFrom(new Db(), yaml);
        assertEquals("the JDBC url", c.getComment("jdbc-url"));
        assertEquals(Arrays.asList("Connection settings"), c.getCommentTree().getHeader());
    }

    @Test
    void overrideCommentReplacesAnExistingComment() {
        final Config c = new Config();
        c.setComment("jdbc-url", "OLD");
        c.mergeFrom(new Db(), yaml); // url's @Comment defaults to OVERRIDE
        assertEquals("the JDBC url", c.getComment("jdbc-url"));
    }

    @Test
    void setIfAbsentCommentPreservesAnExistingComment() {
        final Config c = new Config();
        c.setComment("retries", "USER WROTE THIS");
        c.mergeFrom(new Db(), yaml); // retries' @Comment is SET_IF_ABSENT
        assertEquals("USER WROTE THIS", c.getComment("retries"));
    }

    @Test
    void obsoleteKeyPreservedByDefaultRemovedOnlyUnderRemove() {
        final Config preserve = configFrom("{\"maxPool\":1,\"obsoleteKey\":true}");
        preserve.bind(Db.class, json).writeEntity(new Db());
        assertTrue(preserve.contains("obsoleteKey")); // PRESERVE default keeps it

        final Config remove = configFrom("{\"maxPool\":1,\"obsoleteKey\":true}");
        remove.bind(Db.class, json,
                BindOptions.defaults().withObsoletePolicy(BindOptions.ObsoletePolicy.REMOVE))
                .writeEntity(new Db());
        assertFalse(remove.contains("obsoleteKey")); // REMOVE strips the undeclared key
        assertEquals(10, remove.getInt("maxPool"));   // declared key kept
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
        final SchemaCache cache = new SchemaCache(json.objectMapper());
        final Schema schema = cache.of(json.objectMapper().constructType(Dtos.PolymorphicPojo.class));

        SmartMerge.mergeInto(canonical, candidate, schema,
                BindOptions.defaults().withObsoletePolicy(BindOptions.ObsoletePolicy.REMOVE),
                new CommentTree(), "", false, '.');

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
                .writeEntity(w);

        assertEquals(9, c.getInt("limits.a")); // overwritten by the POJO
        assertTrue(c.contains("limits.b"));    // preserved: the map is open
        assertEquals(2, c.getInt("limits.b"));
    }
}
