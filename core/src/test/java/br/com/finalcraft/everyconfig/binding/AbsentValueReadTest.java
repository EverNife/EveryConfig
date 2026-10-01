package br.com.finalcraft.everyconfig.binding;

import br.com.finalcraft.everyconfig.codec.jackson.JsonCodec;
import br.com.finalcraft.everyconfig.config.Config;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A typed read of an absent path reads {@code null} for every type, and a custom deserializer is never handed
 * an empty object it cannot read. {@code readInto} onto a bean still binds the target's defaults.
 */
class AbsentValueReadTest {

    static final AtomicInteger POINT_READS = new AtomicInteger();

    @JsonDeserialize(using = PointDeserializer.class)
    static class Point {
        final double x;

        Point(final double x) {
            this.x = x;
        }
    }

    static class PointDeserializer extends StdDeserializer<Point> {
        PointDeserializer() {
            super(Point.class);
        }

        @Override
        public Point deserialize(final JsonParser parser, final DeserializationContext context) throws IOException {
            POINT_READS.incrementAndGet();
            final JsonNode node = parser.readValueAsTree();
            return new Point(node.get("x").asDouble()); // NPE on an empty object, like a real value codec
        }
    }

    static class Settings {
        public int port = 25565;
    }

    private final JsonCodec codec = new JsonCodec();

    private Config configFrom(final String json) {
        return new Config((ObjectNode) codec.readTree(json));
    }

    @BeforeEach
    void resetCounter() {
        POINT_READS.set(0);
    }

    @Test
    void absentCustomDeserializedTypeReadsNullWithoutCallingTheDeserializer() {
        assertNull(configFrom("{}").getValue("spawn", Point.class, codec));
        assertEquals(0, POINT_READS.get());
    }

    @Test
    void absentCustomDeserializedTypeLeavesTheTargetUntouched() {
        final Point target = new Point(3);
        final EntityBinder<Point> binder = configFrom("{}").bind(Point.class, codec);
        assertSame(target, binder.readInto("spawn", target));
        assertEquals(3, target.x);
        assertEquals(0, POINT_READS.get());
        assertEquals(0, binder.lastLoadIssues().size());
    }

    @Test
    void absentBeanTypeReadsNull() {
        assertNull(configFrom("{}").getValue("settings", Settings.class, codec));
    }

    @Test
    void absentBeanTypeReadIntoKeepsTheTargetDefaults() {
        final Settings target = new Settings();
        assertSame(target, configFrom("{}").bind(Settings.class, codec).readInto("settings", target));
        assertEquals(25565, target.port);
    }

    @Test
    void emptyBeanObjectStillReadsItsDefaults() {
        assertEquals(25565, configFrom("{\"settings\":{}}").getValue("settings", Settings.class, codec).port);
    }

    @Test
    void absentContainerReadsNull() {
        assertNull(configFrom("{}").getValue("map", Map.class, codec));
    }

    @Test
    void presentCustomDeserializedTypeStillBinds() {
        assertEquals(1.5, configFrom("{\"spawn\":{\"x\":1.5}}").getValue("spawn", Point.class, codec).x);
    }
}
