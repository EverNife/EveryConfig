package br.com.finalcraft.everyconfig.binding;

import br.com.finalcraft.everyconfig.annotation.PostLoad;
import br.com.finalcraft.everyconfig.codec.jackson.JsonCodec;
import br.com.finalcraft.everyconfig.config.Config;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** READ path: missing keys keep defaults, unknown keys are ignored, lenient tolerates bad values while
 *  strict throws, and @PostLoad runs after binding. */
class EntityBinderReadTest {

    static class Cfg {
        public int port = 25565;
        public String name = "default";
        public boolean enabled = true;
        boolean postRan = false;

        @PostLoad
        void init() {
            postRan = true;
        }
    }

    static class Validated {
        public int port = 1;
        List<LoadIssue> seen;

        @PostLoad
        void check(final List<LoadIssue> issues) {
            seen = issues;
        }
    }

    static class Nested {
        public String label = "def";
        public Inner inner = new Inner();

        static class Inner {
            public String url = "defUrl";
            public int poolSize = 7;
        }
    }

    static class WithList {
        public List<Integer> weights;
    }

    private final JsonCodec codec = new JsonCodec();

    private Config configFrom(final String json) {
        return new Config((ObjectNode) codec.readTree(json));
    }

    @Test
    void missingKeysKeepDefaultsAndPostLoadRuns() {
        final Cfg cfg = configFrom("{\"name\":\"prod\"}").loadAs(Cfg.class, codec);
        assertEquals(25565, cfg.port);   // absent -> default kept
        assertEquals("prod", cfg.name);  // present -> bound
        assertTrue(cfg.enabled);         // absent -> default kept
        assertTrue(cfg.postRan);         // @PostLoad ran
    }

    @Test
    void unknownKeysAreIgnored() {
        final Cfg cfg = configFrom("{\"port\":1,\"unknownKey\":true}").loadAs(Cfg.class, codec);
        assertEquals(1, cfg.port);
    }

    @Test
    void lenientRecordsIssueAndDoesNotThrow() {
        final EntityBinder<Cfg> binder = configFrom("{\"port\":\"NaN\",\"name\":\"x\"}").bind(Cfg.class, codec);
        final Cfg cfg = binder.read("");
        assertFalse(binder.lastLoadIssues().isEmpty()); // the bad port was recorded
        assertEquals(25565, cfg.port);                  // the bad value was skipped: the default is kept
        assertEquals("x", cfg.name);                    // the rest still bound
    }

    @Test
    void strictThrowsOnBadValue() {
        final EntityBinder<Cfg> binder = configFrom("{\"port\":\"NaN\"}")
                .bind(Cfg.class, codec, BindOptions.defaults().withCoercion(BindOptions.Coercion.STRICT));
        assertThrows(BindException.class, () -> binder.read(""));
    }

    @Test
    void postInjectReceivesIssuesList() {
        final Validated v = configFrom("{\"port\":2}").loadAs(Validated.class, codec);
        assertNotNull(v.seen);
        assertTrue(v.seen.isEmpty());
        assertEquals(2, v.port);
    }

    @Test
    void lenientIsolatesNestedBadValueByDottedKey() {
        final EntityBinder<Nested> binder = configFrom(
                "{\"label\":\"ok\",\"inner\":{\"url\":\"good\",\"poolSize\":\"NaN\"}}")
                .bind(Nested.class, codec);
        final Nested n = binder.read("");
        assertEquals("ok", n.label);
        assertEquals("good", n.inner.url);       // the good sibling still bound
        assertEquals(7, n.inner.poolSize);       // the bad leaf kept its default
        assertEquals(1, binder.lastLoadIssues().size());
        assertEquals("inner.poolSize", binder.lastLoadIssues().get(0).key());
    }

    @Test
    void lenientIsolatesBadListElementByIndexedKey() {
        final EntityBinder<WithList> binder = configFrom("{\"weights\":[1,\"x\",3]}")
                .bind(WithList.class, codec);
        final WithList w = binder.read("");
        assertEquals(Integer.valueOf(1), w.weights.get(0)); // good elements survive at their indices
        assertEquals(Integer.valueOf(3), w.weights.get(2));
        assertEquals("weights[1]", binder.lastLoadIssues().get(0).key());
    }

    @Test
    void readResultCarriesValueAndIssues() {
        final EntityBinder<Cfg> binder = configFrom("{\"port\":\"NaN\",\"name\":\"x\"}").bind(Cfg.class, codec);
        final BindResult<Cfg> r = binder.readResult("");
        assertEquals(25565, r.value().port);                 // bad value skipped, default kept
        assertEquals("x", r.value().name);
        assertTrue(r.hasIssues());
        assertEquals(r.issues(), binder.lastLoadIssues());   // the two channels agree
        assertThrows(UnsupportedOperationException.class, () -> r.issues().add(null)); // snapshot is immutable
    }

    @Test
    void loadAsResultExposesIssuesThatLoadAsHides() {
        final BindResult<Cfg> r = configFrom("{\"port\":\"NaN\"}").loadAsResult(Cfg.class, codec);
        assertEquals(25565, r.value().port);
        assertTrue(r.hasIssues());
    }

    @Test
    void readResultIsCleanWhenNoIssues() {
        final BindResult<Cfg> r = configFrom("{\"port\":2}").bind(Cfg.class, codec).readResult("");
        assertEquals(2, r.value().port);
        assertFalse(r.hasIssues());
        assertTrue(r.issues().isEmpty());
    }
}
