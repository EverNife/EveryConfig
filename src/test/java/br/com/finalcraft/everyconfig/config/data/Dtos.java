package br.com.finalcraft.everyconfig.config.data;

import br.com.finalcraft.everyconfig.annotation.Comment;
import br.com.finalcraft.everyconfig.annotation.CommentMode;
import br.com.finalcraft.everyconfig.annotation.KeyIndex;
import br.com.finalcraft.everyconfig.annotation.Key;
import br.com.finalcraft.everyconfig.annotation.KeyTransformCase;
import br.com.finalcraft.everyconfig.annotation.PostLoad;
import br.com.finalcraft.everyconfig.annotation.Section;
import br.com.finalcraft.everyconfig.binding.ConfigContext;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.binding.LoadIssueAware;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Shared, top-level test DTOs for the codec-agnostic config contract suite. Each scenario the contract
 * exercises has a dedicated nested type here so the same fixtures run unchanged across every codec
 * subclass (JSON / YAML / TOML / JSONC). Value DTOs use Lombok {@code @Data} (public fields kept for
 * direct access, plus generated equals/hashCode for round-trip comparison); annotation-driven DTOs use
 * plain fields to keep the binding annotations on the field exactly as the introspector expects.
 *
 * <p>Java 8 runtime floor: no records, no {@code List.of}; use {@code Arrays.asList}/{@code LinkedHashMap}.
 */
public final class Dtos {

    private Dtos() {
    }

    // ===================== plain value DTOs (no annotations) =====================

    /** Baseline: every primitive family, no annotations. */
    @Data
    @NoArgsConstructor
    public static class PlainPojo {
        public String name;
        public int count;
        public boolean active;
        public double ratio;
        public long epoch;
    }

    /** POJO-in-POJO (non-section nesting). */
    @Data
    @NoArgsConstructor
    public static class NestedPojo {
        public String label;
        public Inner inner;

        @Data
        @NoArgsConstructor
        public static class Inner {
            public String url;
            public int poolSize;
        }
    }

    /** Lists + a map; proves order and value coercion through the codec. */
    @Data
    @NoArgsConstructor
    public static class CollectionsPojo {
        public List<String> tags;
        public List<Integer> weights;
        public Map<String, Integer> limits;
    }

    /** A list whose every element is a non-empty object (exercises TOML's {@code [[array-of-tables]]}). */
    @Data
    @NoArgsConstructor
    public static class ListOfPojoPojo {
        public String title;
        public List<Server> servers;

        @Data
        @NoArgsConstructor
        public static class Server {
            public String name;
            public int port;
        }
    }

    /** java.time spread (jsr310). */
    @Data
    @NoArgsConstructor
    public static class TemporalPojo {
        public Instant instant;
        public LocalDate date;
        public LocalDateTime dateTime;
        public Duration dur;
    }

    /** Optional / OptionalInt (jdk8 module). */
    @Data
    @NoArgsConstructor
    public static class OptionalPojo {
        public Optional<String> present;
        public Optional<String> empty;
        public OptionalInt num;
    }

    /** Numeric edges: a real long, big integer/decimal near the precision frontier. */
    @Data
    @NoArgsConstructor
    public static class NumericEdgePojo {
        public long bigLong;
        public int port;
        public double pi;
        public BigInteger huge;
        public BigDecimal precise;
    }

    /** Null vs empty-string distinction. */
    @Data
    @NoArgsConstructor
    public static class NullablePojo {
        public String nullable;
        public String emptyStr;
        public Integer nullableNum;
    }

    /** Schema evolution: the file carries keys this type no longer declares; they must survive. */
    @Data
    @NoArgsConstructor
    public static class EvolutionPojo {
        public String known;
        public int version;
    }

    /** Enum by name + an enum carrying an instance field (must still serialize as the plain name). */
    @Data
    @NoArgsConstructor
    public static class EnumPojo {
        public Mode mode;
        public Transport transport;
    }

    public enum Mode {
        FAST, SLOW
    }

    public enum Transport {
        NIO("nio"), EPOLL("epoll");

        private final String code;

        Transport(final String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    // ===================== annotation-driven DTOs =====================

    /** {@code @Key} rename + case transforms. */
    public static class KeyNamingPojo {
        @Key(transformCase = KeyTransformCase.KEBAB_CASE)
        public int maxPoolSize = 10;
        @Key("custom-host")
        public String host = "localhost";
        @Key(transformCase = KeyTransformCase.SNAKE_CASE)
        public int ttlSeconds = 30;
    }

    /** Field comments in both modes + a class-level header. */
    @Comment(value = "Database settings", mode = CommentMode.SET_IF_ABSENT)
    public static class CommentedPojo {
        @Comment("The JDBC connection url")
        public String jdbcUrl = "jdbc:h2:mem:test";
        @Comment("Connection timeout, seconds")
        public int timeout = 30;
        @Comment(value = "Tune this if needed", mode = CommentMode.SET_IF_ABSENT)
        public int retries = 3;
        public String mode = "rw";
    }

    /** {@code @Section} placement: a flat field relocated to a nested path. */
    public static class SectionedPojo {
        @Section("database.pool")
        @Key(transformCase = KeyTransformCase.KEBAB_CASE)
        public int maxSize = 50;
        public String name = "main";
    }

    /** A {@code @Section} field living INSIDE a nested POJO (not at the root); exercises nested relocation. */
    public static class NestedSectionPojo {
        public String name = "main";
        public Inner inner = new Inner();

        public static class Inner {
            @Section("limits")
            @Key(transformCase = KeyTransformCase.KEBAB_CASE)
            public int maxSize = 50;
        }
    }

    // ----- @KeyIndex collection elements -----

    /** String {@code @KeyIndex}. */
    public static class KeyIndexAccountPojo {
        @KeyIndex
        public String name;
        public int balance;

        public KeyIndexAccountPojo() {
        }

        public KeyIndexAccountPojo(final String name, final int balance) {
            this.name = name;
            this.balance = balance;
        }
    }

    /** Non-String {@code @KeyIndex} (UUID) — exercises key casting. */
    public static class KeyIndexUuidPojo {
        @KeyIndex
        public UUID id;
        public String label;

        public KeyIndexUuidPojo() {
        }

        public KeyIndexUuidPojo(final UUID id, final String label) {
            this.id = id;
            this.label = label;
        }
    }

    /** Non-String {@code @KeyIndex} (int). */
    public static class KeyIndexIntPojo {
        @KeyIndex
        public int id;
        public long score;

        public KeyIndexIntPojo() {
        }

        public KeyIndexIntPojo(final int id, final long score) {
            this.id = id;
            this.score = score;
        }
    }

    /** No {@code @KeyIndex} — id-collection write must reject it. */
    public static class NoKeyIndexPojo {
        public int x;
    }

    /** Two {@code @KeyIndex} fields — must be rejected. */
    public static class DualKeyIndexPojo {
        @KeyIndex
        public String a;
        @KeyIndex
        public String b;
    }

    // ----- @PostLoad -----

    /** No-arg {@code @PostLoad}. */
    public static class PostLoadPojo {
        public int port = 1;
        public String name = "def";
        public boolean enabled = true;
        public transient boolean initialized = false;

        @PostLoad
        void init() {
            initialized = true;
        }
    }

    /** {@code @PostLoad} reading the issues off the {@code ConfigContext}. */
    public static class PostLoadIssuesPojo {
        public int port = 1;
        public transient List<LoadIssue> seen;

        @PostLoad
        void check(final ConfigContext context) {
            seen = context.issues();
        }
    }

    /** A {@code @PostLoad} that throws — binding must surface it as a {@code BindException}. */
    public static class PostLoadThrowsPojo {
        public int port = 1;

        @PostLoad
        void boom() {
            throw new IllegalStateException("post-inject failure");
        }
    }

    /** Inherited {@code @PostLoad}: an overridden hook must run once (de-duped by method name). */
    public static class InheritedPostLoadBase {
        public transient int hookCalls = 0;

        @PostLoad
        void hook() {
            hookCalls++;
        }
    }

    public static class InheritedPostLoadSub extends InheritedPostLoadBase {
        @Override
        @PostLoad
        void hook() {
            hookCalls++;
        }
    }

    // ----- LoadIssueAware -----

    /** Receives the bind's collected issues via {@link LoadIssueAware}. */
    public static class IssueAwarePojo implements LoadIssueAware {
        public int count;
        public transient List<LoadIssue> received;

        @Override
        public void setLoadIssues(final List<LoadIssue> issues) {
            this.received = issues;
        }
    }

    // ===================== polymorphism =====================

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Circle.class, name = "circle"),
            @JsonSubTypes.Type(value = Square.class, name = "square")
    })
    public abstract static class Shape {
    }

    public static class Circle extends Shape {
        public double radius;

        public Circle() {
        }

        public Circle(final double radius) {
            this.radius = radius;
        }
    }

    public static class Square extends Shape {
        public double side;

        public Square() {
        }

        public Square(final double side) {
            this.side = side;
        }
    }

    /** Holder for a polymorphic field; exercises {@code @JsonTypeInfo} survival through SmartMerge. */
    public static class PolymorphicPojo {
        public Shape shape;
        public String label = "shapes";
    }

    // ===================== helpers =====================

    /** A {@code LinkedHashMap} preserving insertion order (Java-8 safe; no Map.of). */
    public static Map<String, Integer> orderedLimits() {
        final Map<String, Integer> m = new LinkedHashMap<>();
        m.put("ok", 40);
        m.put("errors", 2);
        m.put("warnings", 7);
        return m;
    }
}
