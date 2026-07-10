package br.com.finalcraft.everyconfig.binding.merge;

import br.com.finalcraft.everyconfig.annotation.PostLoad;
import br.com.finalcraft.everyconfig.binding.ConfigContext;
import br.com.finalcraft.everyconfig.binding.ConfigLifecycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the codec-independent pieces of the nested-lifecycle machinery: the {@code hasHooks}
 * predicate, the conservative {@code mayContainHooks} static gate (whose only forbidden outcome is a false
 * negative), and the once-per-type compact-element warning.
 */
class LifecycleGraphWalkerTest {

    // ---- fixtures ----

    static final class Leaf {
        public int a;
        public String b;
        public boolean c;
    }

    /** All fields bottom out in JDK/enum/primitive leaves (including scalar containers) — provably hook-free. */
    static final class FlatScalar {
        public int a;
        public String b;
        public List<String> tags;
        public Map<String, Integer> limits;
        public int[] nums;
        public Leaf leaf; // a final POJO with only scalar fields: still provable
    }

    static class HookByInterface implements ConfigLifecycle {
        public int a;
    }

    static class HookByAnnotation {
        public int a;

        @PostLoad
        void after(final ConfigContext ctx) {
        }
    }

    static class Plain {
        public int a;
    }

    static class FieldOwner {
        public FlatScalar flat;
        public HookByInterface hooked; // reachable hook-bearing field
    }

    static class ListOwner {
        public List<HookByInterface> xs;
    }

    static class MapOwner {
        public Map<String, HookByAnnotation> m;
    }

    /** Used only here, so the static one-time warn guard is exercised cleanly. */
    static class CompactWarnType implements ConfigLifecycle {
    }

    // ---- hasHooks ----

    @Test
    @DisplayName("hasHooks: true for a ConfigLifecycle impl or an @Pre/@Post method; false otherwise")
    void hasHooks_detectsInterfaceAndAnnotations() {
        assertTrue(LifecycleInvoker.hasHooks(HookByInterface.class));
        assertTrue(LifecycleInvoker.hasHooks(HookByAnnotation.class));
        assertFalse(LifecycleInvoker.hasHooks(Plain.class));
        assertFalse(LifecycleInvoker.hasHooks(FlatScalar.class));
    }

    // ---- the static gate ----

    @Test
    @DisplayName("mayContainHooks: false for a provably flat/scalar graph")
    void mayContainHooks_falseForFlatScalarGraph() {
        assertFalse(LifecycleGraphWalker.mayContainHooks(FlatScalar.class));
        assertFalse(LifecycleGraphWalker.mayContainHooks(Plain.class));
        assertFalse(LifecycleGraphWalker.mayContainHooks(String.class));
    }

    @Test
    @DisplayName("mayContainHooks: true when a hook-bearing type is reachable by field, list element, or map value")
    void mayContainHooks_trueWhenHookReachable() {
        assertTrue(LifecycleGraphWalker.mayContainHooks(HookByInterface.class));
        assertTrue(LifecycleGraphWalker.mayContainHooks(FieldOwner.class));
        assertTrue(LifecycleGraphWalker.mayContainHooks(ListOwner.class));
        assertTrue(LifecycleGraphWalker.mayContainHooks(MapOwner.class));
    }

    // ---- compact-element warning ----

    @Test
    @DisplayName("warnCompactHooks: warns once per hook-bearing type, never for a hook-free type")
    void warnCompactHooks_warnsOncePerType() {
        final Logger log = Logger.getLogger(LifecycleGraphWalker.class.getName());
        log.setLevel(Level.ALL);
        final List<LogRecord> records = new ArrayList<LogRecord>();
        final Handler handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        log.addHandler(handler);
        try {
            LifecycleGraphWalker.warnCompactHooks(CompactWarnType.class);
            LifecycleGraphWalker.warnCompactHooks(CompactWarnType.class); // deduped by the static guard
            assertEquals(1, records.size(), "expected exactly one warning for the hook-bearing compact type");
            records.clear();
            LifecycleGraphWalker.warnCompactHooks(Plain.class); // no hooks -> no warning
            assertEquals(0, records.size());
        } finally {
            log.removeHandler(handler);
        }
    }
}
