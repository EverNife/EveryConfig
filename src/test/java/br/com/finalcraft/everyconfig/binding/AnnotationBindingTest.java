package br.com.finalcraft.everyconfig.binding;

import br.com.finalcraft.everyconfig.annotation.Key;
import br.com.finalcraft.everyconfig.annotation.KeyTransformCase;
import br.com.finalcraft.everyconfig.binding.introspect.KeyCaseStrategy;
import br.com.finalcraft.everyconfig.codec.jackson.JsonCodec;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The class-wide naming strategy ({@code @JsonNaming(KeyCaseStrategy.*)}): kebab/snake applied to every
 * property, with a per-field {@code @Key} overriding it. This is mapper-level (codec-independent), so it
 * stays here as a focused introspector unit test; the per-field {@code @Key} rename + case transform and
 * enum-by-name round trips are exercised across every codec in the codec-agnostic contract.
 */
class AnnotationBindingTest {

    /** Class-wide kebab via @JsonNaming; the two @Key fields prove per-field precedence over the strategy. */
    @JsonNaming(KeyCaseStrategy.Kebab.class)
    static class KebabClass {
        public int maxPoolSize = 10;
        public String serverHost = "h";

        @Key("explicit_literal")
        public String forced = "f";

        @Key(transformCase = KeyTransformCase.SNAKE_CASE)
        public int twoWords = 3;
    }

    @JsonNaming(KeyCaseStrategy.Snake.class)
    static class SnakeClass {
        public int maxPoolSize = 10;
    }

    private final JsonCodec codec = new JsonCodec();

    @Test
    void classWideKebabApplied() {
        final JsonNode tree = codec.valueToTree(new KebabClass());
        assertTrue(tree.has("max-pool-size"), tree.toString());
        assertTrue(tree.has("server-host"), tree.toString());
        assertEquals(10, tree.get("max-pool-size").asInt());
    }

    @Test
    void classWideKebabBoundBack() {
        final JsonNode tree = codec.readTree("{\"max-pool-size\":25,\"server-host\":\"z\"}");
        final JavaType type = codec.getObjectMapper().constructType(KebabClass.class);
        final KebabClass dto = codec.treeToValue(tree, type);
        assertEquals(25, dto.maxPoolSize);
        assertEquals("z", dto.serverHost);
    }

    @Test
    void classWideSnakeApplied() {
        final JsonNode tree = codec.valueToTree(new SnakeClass());
        assertTrue(tree.has("max_pool_size"), tree.toString());
        assertEquals(10, tree.get("max_pool_size").asInt());
    }

    @Test
    void perFieldKeyOverridesClassStrategy() {
        final JsonNode tree = codec.valueToTree(new KebabClass());
        assertTrue(tree.has("explicit_literal"), tree.toString()); // @Key literal kept verbatim
        assertTrue(tree.has("two_words"), tree.toString());        // field snake wins over class kebab
        assertFalse(tree.has("two-words"), tree.toString());
    }
}
