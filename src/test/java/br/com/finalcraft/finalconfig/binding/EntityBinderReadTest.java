package br.com.finalcraft.finalconfig.binding;

import br.com.finalcraft.finalconfig.annotation.PostInject;
import br.com.finalcraft.finalconfig.codec.jackson.JsonCodec;
import br.com.finalcraft.finalconfig.config.Config;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** READ path: missing keys keep defaults, unknown keys are ignored, lenient tolerates bad values while
 *  strict throws, and @PostInject runs after binding. */
class EntityBinderReadTest {

    static class Cfg {
        public int port = 25565;
        public String name = "default";
        public boolean enabled = true;
        boolean postRan = false;

        @PostInject
        void init() {
            postRan = true;
        }
    }

    static class Validated {
        public int port = 1;
        List<LoadIssue> seen;

        @PostInject
        void check(final List<LoadIssue> issues) {
            seen = issues;
        }
    }

    private final JsonCodec codec = new JsonCodec();

    private Config configFrom(final String json) {
        return new Config((ObjectNode) codec.readTree(json));
    }

    @Test
    void missingKeysKeepDefaultsAndPostInjectRuns() {
        final Cfg cfg = configFrom("{\"name\":\"prod\"}").loadAs(Cfg.class, codec);
        assertEquals(25565, cfg.port);   // absent -> default kept
        assertEquals("prod", cfg.name);  // present -> bound
        assertTrue(cfg.enabled);         // absent -> default kept
        assertTrue(cfg.postRan);         // @PostInject ran
    }

    @Test
    void unknownKeysAreIgnored() {
        final Cfg cfg = configFrom("{\"port\":1,\"unknownKey\":true}").loadAs(Cfg.class, codec);
        assertEquals(1, cfg.port);
    }

    @Test
    void lenientRecordsIssueAndDoesNotThrow() {
        final EntityBinder<Cfg> binder = configFrom("{\"port\":\"NaN\",\"name\":\"x\"}").bind(Cfg.class, codec);
        final Cfg cfg = binder.bind();
        assertFalse(binder.lastLoadIssues().isEmpty()); // the bad port was recorded
        assertEquals(25565, cfg.port);                  // the bad value was skipped: the default is kept
        assertEquals("x", cfg.name);                    // the rest still bound
    }

    @Test
    void strictThrowsOnBadValue() {
        final EntityBinder<Cfg> binder = configFrom("{\"port\":\"NaN\"}")
                .bind(Cfg.class, codec, BindOptions.defaults().withCoercion(BindOptions.Coercion.STRICT));
        assertThrows(BindException.class, binder::bind);
    }

    @Test
    void postInjectReceivesIssuesList() {
        final Validated v = configFrom("{\"port\":2}").loadAs(Validated.class, codec);
        assertNotNull(v.seen);
        assertTrue(v.seen.isEmpty());
        assertEquals(2, v.port);
    }
}
