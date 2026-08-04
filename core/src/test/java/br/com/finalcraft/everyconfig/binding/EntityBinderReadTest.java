package br.com.finalcraft.everyconfig.binding;

import br.com.finalcraft.everyconfig.codec.jackson.JsonCodec;
import br.com.finalcraft.everyconfig.config.Config;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The binder's result-carrying read API ({@code readResult} / {@code loadAsResult}): the returned
 * {@link BindResult} carries the value plus an immutable snapshot of the {@link LoadIssue}s — the same
 * issues the stateful {@code lastLoadIssues()} channel exposes. The codec-agnostic read behaviors (missing
 * keys keep defaults, unknown keys ignored, lenient vs strict, per-key isolation, @PostLoad) live in the
 * cross-codec contract.
 */
class EntityBinderReadTest {

    static class Cfg {
        public int port = 25565;
        public String name = "default";
    }

    private final JsonCodec codec = new JsonCodec();

    private Config configFrom(final String json) {
        return new Config((ObjectNode) codec.readTree(json));
    }

    @Test
    void readResultCarriesValueAndAnImmutableIssueSnapshotMatchingTheStatefulChannel() {
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
}
