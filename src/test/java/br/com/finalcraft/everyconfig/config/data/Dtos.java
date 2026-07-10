package br.com.finalcraft.everyconfig.config.data;

import br.com.finalcraft.everyconfig.annotation.Comment;
import br.com.finalcraft.everyconfig.annotation.CommentMode;
import br.com.finalcraft.everyconfig.annotation.EveryConfigCompactCreator;
import br.com.finalcraft.everyconfig.annotation.EveryConfigCompactValue;
import br.com.finalcraft.everyconfig.annotation.KeyIndex;
import br.com.finalcraft.everyconfig.annotation.Key;
import br.com.finalcraft.everyconfig.annotation.KeyTransformCase;
import br.com.finalcraft.everyconfig.annotation.PostLoad;
import br.com.finalcraft.everyconfig.annotation.PostSave;
import br.com.finalcraft.everyconfig.annotation.PreLoad;
import br.com.finalcraft.everyconfig.annotation.PreSave;
import br.com.finalcraft.everyconfig.annotation.Section;
import br.com.finalcraft.everyconfig.binding.ConfigContext;
import br.com.finalcraft.everyconfig.binding.ConfigLifecycle;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.binding.LoadIssueAware;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    @Data
    @NoArgsConstructor
    public static class PlainPojoPartial {
        public String name;
        public boolean active;
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

    /** Class-level {@code @Comment} in OVERRIDE mode (the default) — seeds/overwrites the file header. */
    @Comment("Generated header")
    public static class ClassHeaderOverridePojo {
        public int value = 1;
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

    /** A {@code @Section} field carrying a {@code @Comment}; the comment must land at the nested path. */
    public static class SectionCommentedPojo {
        @Section("db")
        @Comment("the pool size")
        public int poolSize = 5; // -> db.poolSize, with the comment at that nested path
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

    // ----- lifecycle hooks (annotation + interface) -----

    /** All four lifecycle hooks as method annotations; {@code trace} records each firing in order (transient
     *  so the binder never serializes it). */
    public static class LifecycleTrackedPojo {
        public String name = "def";
        public transient List<String> trace = new ArrayList<String>();

        @PreLoad
        void before() {
            trace.add("preLoad:" + name); // name is still the default here (tree not applied yet)
        }

        @PostLoad
        void after() {
            trace.add("postLoad:" + name); // name now reflects the tree
        }

        @PreSave
        void beforeSave() {
            trace.add("preSave");
        }

        @PostSave
        void afterSave() {
            trace.add("postSave");
        }
    }

    /** The opt-in {@link ConfigLifecycle} interface; each callback records the section path it was handed. */
    public static class LifecycleInterfacePojo implements ConfigLifecycle {
        public String name = "def";
        public transient List<String> calls = new ArrayList<String>();

        @Override
        public void preLoad(final ConfigContext context) {
            calls.add("preLoad@" + context.section().getPath());
        }

        @Override
        public void postLoad(final ConfigContext context) {
            calls.add("postLoad@" + context.section().getPath());
        }

        @Override
        public void preSave(final ConfigContext context) {
            calls.add("preSave@" + context.section().getPath());
        }

        @Override
        public void postSave(final ConfigContext context) {
            calls.add("postSave@" + context.section().getPath());
        }
    }

    // ===================== nested lifecycle composition =====================

    /**
     * A {@link ConfigLifecycle} entity whose {@code postSave} writes an {@code extra} child through its own
     * section and whose {@code postLoad} reads it back — the shape that only works if the hooks fire wherever
     * the type is (de)serialized, nested included. {@code extra} is app data persisted manually (transient, so
     * the mapper never emits it); {@code fires} records {@code "phase@path"} so a test can read the sub-path
     * each hook was handed.
     */
    public static class HookedPojo implements ConfigLifecycle {
        public String name = "";
        public int value = 0;
        public transient String extra;                                  // persisted manually to <section>.extra
        public transient List<String> fires = new ArrayList<String>();

        public HookedPojo() {
        }

        public HookedPojo(final String name, final int value, final String extra) {
            this.name = name;
            this.value = value;
            this.extra = extra;
        }

        @Override
        public void preLoad(final ConfigContext context) {
            fires.add("preLoad@" + context.section().getPath());
        }

        @Override
        public void postLoad(final ConfigContext context) {
            fires.add("postLoad@" + context.section().getPath());
            this.extra = context.section().getString("extra");
        }

        @Override
        public void preSave(final ConfigContext context) {
            fires.add("preSave@" + context.section().getPath());
        }

        @Override
        public void postSave(final ConfigContext context) {
            fires.add("postSave@" + context.section().getPath());
            if (extra != null) {
                context.section().setValue("extra", extra);
            }
        }
    }

    /** As {@link HookedPojo} but with a {@code @KeyIndex} id, so a collection of it serializes key-major and
     *  each element's section is {@code base.<id>}. */
    public static class HookedKeyedPojo implements ConfigLifecycle {
        @KeyIndex
        public String id = "";
        public int value = 0;
        public transient String extra;
        public transient List<String> fires = new ArrayList<String>();

        public HookedKeyedPojo() {
        }

        public HookedKeyedPojo(final String id, final int value, final String extra) {
            this.id = id;
            this.value = value;
            this.extra = extra;
        }

        @Override
        public void postLoad(final ConfigContext context) {
            fires.add("postLoad@" + context.section().getPath());
            this.extra = context.section().getString("extra");
        }

        @Override
        public void postSave(final ConfigContext context) {
            fires.add("postSave@" + context.section().getPath());
            if (extra != null) {
                context.section().setValue("extra", extra);
            }
        }
    }

    /** A top-level entity holding hook-bearing entities in every nested shape: a plain field, a {@code Map}
     *  value, and a {@code List} element. Implements {@link ConfigLifecycle} itself so its own top-level
     *  {@code preLoad}/{@code postLoad} fire — letting a test assert that {@code PRE_LOAD} does NOT propagate
     *  to the nested entities. */
    public static class HookedOwnerPojo implements ConfigLifecycle {
        public String title = "owner";
        public HookedPojo child;
        public Map<String, HookedPojo> byName = new LinkedHashMap<String, HookedPojo>();
        public List<HookedPojo> items = new ArrayList<HookedPojo>();
        public transient List<String> fires = new ArrayList<String>();

        @Override
        public void preLoad(final ConfigContext context) {
            fires.add("preLoad@" + context.section().getPath());
        }

        @Override
        public void postLoad(final ConfigContext context) {
            fires.add("postLoad@" + context.section().getPath());
        }
    }

    /** A compact-element type that ALSO carries lifecycle hooks — the collision case: as a compact list
     *  element it collapses to one string with no sub-path, so its hooks cannot compose (EveryConfig warns
     *  rather than firing at a bogus section). {@code fires} must stay empty when it is used compactly. */
    public static class CompactHookedPojo implements ConfigLifecycle {
        public int n = 0;
        public transient List<String> fires = new ArrayList<String>();

        public CompactHookedPojo() {
        }

        public CompactHookedPojo(final int n) {
            this.n = n;
        }

        @EveryConfigCompactValue
        public String toElementString() {
            return String.valueOf(n);
        }

        @EveryConfigCompactCreator
        public static CompactHookedPojo fromElementString(final String s) {
            return new CompactHookedPojo(Integer.parseInt(s.trim()));
        }

        @Override
        public void postSave(final ConfigContext context) {
            fires.add("postSave@" + context.section().getPath());
        }

        @Override
        public void postLoad(final ConfigContext context) {
            fires.add("postLoad@" + context.section().getPath());
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

    // ===================== self-describing types (Jackson-native) =====================

    /**
     * Scalar self-describing type: serializes to a single string via {@code @JsonValue} and rebuilds from
     * it via a {@code @JsonCreator} static factory. Round-trips through EveryConfig with NO central
     * registration — purely by virtue of the Jackson-first shared mapper honoring the annotations.
     */
    @EqualsAndHashCode
    @ToString
    public static class SelfDescribingScalar {
        public final int x;
        public final int y;

        public SelfDescribingScalar(final int x, final int y) {
            this.x = x;
            this.y = y;
        }

        @JsonValue
        public String toConfigString() {
            return x + ":" + y;
        }

        @JsonCreator
        public static SelfDescribingScalar fromConfigString(final String s) {
            final String[] parts = s.split(":");
            return new SelfDescribingScalar(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }

    }

    /**
     * Immutable object self-describing type: serializes to an object via its public fields and rebuilds via
     * a {@code @JsonCreator} constructor with {@code @JsonProperty} args (no no-arg constructor, no setters).
     * Exercises the creator-with-properties path through the shared mapper.
     */
    @EqualsAndHashCode
    @ToString
    public static class SelfDescribingObject {
        public final int width;
        public final int height;

        @JsonCreator
        public SelfDescribingObject(@JsonProperty("width") final int width,
                                    @JsonProperty("height") final int height) {
            this.width = width;
            this.height = height;
        }

    }

    /** Holds self-describing types solo-as-a-field and inside a list, exercising every serialization context. */
    @EqualsAndHashCode
    @ToString
    public static class SelfDescribingHolder {
        public SelfDescribingScalar coord;
        public SelfDescribingObject size;
        public List<SelfDescribingScalar> path = new ArrayList<SelfDescribingScalar>();

    }

    /**
     * An enum declaring a custom {@code @JsonValue} form (a lowercase code) with a {@code @JsonCreator} to
     * read it back. Its self-describing form must win over the plain {@code name()} that EveryConfig forces
     * on undecorated enums — the enum counterpart of {@link SelfDescribingScalar}.
     */
    public enum CodeEnum {
        ALPHA("a"), BETA("b");

        private final String code;

        CodeEnum(final String code) {
            this.code = code;
        }

        @JsonValue
        public String code() {
            return code;
        }

        @JsonCreator
        public static CodeEnum fromCode(final String code) {
            for (final CodeEnum v : values()) {
                if (v.code.equals(code)) {
                    return v;
                }
            }
            throw new IllegalArgumentException("unknown CodeEnum code: " + code);
        }
    }

    /** Holds a {@code @JsonValue} enum as a field, to exercise the mapper (POJO-field) serialization path. */
    public static class JsonValueEnumHolder {
        public CodeEnum mode;
    }

    // ===================== distinct element form (rich solo, compact in a list) =====================

    /**
     * A position with two forms: a RICH object {@code {x,y,z}} when solo/a field (its plain Jackson form), and
     * a COMPACT string {@code "x y z"} when written as a collection element — declared with
     * {@link EveryConfigCompactValue} / {@link EveryConfigCompactCreator}. The annotations do NOT change the
     * solo form (Jackson ignores them); only the dynamic collection path uses the compact one.
     */
    @EqualsAndHashCode
    @ToString
    public static class DualFormPos {
        public int x;
        public int y;
        public int z;

        public DualFormPos() {
        }

        public DualFormPos(final int x, final int y, final int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @EveryConfigCompactValue
        public String toElementString() {
            return x + " " + y + " " + z;
        }

        @EveryConfigCompactCreator
        public static DualFormPos fromElementString(final String s) {
            final String[] parts = s.trim().split("\\s+");
            return new DualFormPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        }

    }

    /**
     * A plain rich position with NO compact annotations — the stand-in for a third-party type EveryConfig cannot
     * annotate. It gets a compact-in-list form only from a consumer {@code CompactElementResolver} attached to
     * the codec; its solo/field form stays a rich object.
     */
    @EqualsAndHashCode
    @ToString
    public static class PlainPos {
        public int x;
        public int y;

        public PlainPos() {
        }

        public PlainPos(final int x, final int y) {
            this.x = x;
            this.y = y;
        }
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
