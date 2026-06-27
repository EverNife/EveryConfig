package br.com.finalcraft.finalconfig.binding;

import br.com.finalcraft.finalconfig.annotation.Comment;
import br.com.finalcraft.finalconfig.annotation.Key;
import br.com.finalcraft.finalconfig.annotation.KeyTransformCase;
import br.com.finalcraft.finalconfig.annotation.Section;
import br.com.finalcraft.finalconfig.codec.jackson.JsonCodec;
import br.com.finalcraft.finalconfig.codec.jackson.YamlCodec;
import br.com.finalcraft.finalconfig.config.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** @Section places a flat field under a nested path on write and restores it to the field on read,
 *  the way the old library's @Key(prefix=...) did. */
class SectionPlacementTest {

    static class Conf {
        @Section("database.pool")
        @Key(transformCase = KeyTransformCase.KEBAB_CASE)
        public int maxSize = 10; // -> database.pool.max-size

        public String name = "main"; // stays at the root
    }

    static class Documented {
        @Section("db")
        @Comment("the pool size")
        public int poolSize = 5; // -> db.poolSize, with the comment at that nested path
    }

    private final JsonCodec json = new JsonCodec();

    @Test
    void sectionFieldIsWrittenNestedAndReadBack() {
        final Config c = new Config();
        final Conf conf = new Conf();
        conf.maxSize = 25;
        c.mergeFrom(conf, json);

        assertEquals(25, c.getInt("database.pool.max-size")); // nested, not flat
        assertFalse(c.contains("max-size"));
        assertEquals("main", c.getString("name"));            // a non-section field stays at the root

        final Conf read = c.loadAs(Conf.class, json);
        assertEquals(25, read.maxSize);                       // restored to the flat field
        assertEquals("main", read.name);
    }

    @Test
    void sectionFieldSeedsItsCommentAtTheNestedPath() {
        final YamlCodec yaml = new YamlCodec();
        final Config c = new Config();
        c.mergeFrom(new Documented(), yaml);
        assertEquals(5, c.getInt("db.poolSize"));
        assertEquals("the pool size", c.getComment("db.poolSize"));
    }
}
