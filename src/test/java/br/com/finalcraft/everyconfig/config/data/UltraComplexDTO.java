package br.com.finalcraft.everyconfig.config.data;

import br.com.finalcraft.everyconfig.annotation.Comment;
import br.com.finalcraft.everyconfig.annotation.Key;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The "kitchen sink" fixture: one DTO that touches every type family the binder/codec stack must survive,
 * deliberately kept in its own file (not crowded into {@link Dtos}) because it exists to stress the round
 * trip end-to-end. Each field documents a distinct hazard — string escaping, numeric boundaries, the
 * floating-point non-finite values, {@code java.time}, every collection flavor, nested generics, a deep
 * relocation chain — so a regression in any one cell of the matrix shows up as a single failed assertion.
 *
 * <p><b>Codec caveats baked into the field choices (verified per codec, asserted accordingly):</b>
 * <ul>
 *   <li>The non-finite doubles ({@link #nanValue}/{@link #positiveInfinity}/{@link #negativeInfinity})
 *       have no TOML representation, so they read back null there; the test gates them on a capability.</li>
 *   <li>{@link #negativeZero} loses its sign on TOML ({@code -0.0 -> 0.0}); the test compares with a delta.</li>
 *   <li>{@link #bigDecimal} keeps its full scale only on TOML — JSON-family parsers read a float as a
 *       {@code double}, collapsing the scale — so the test compares magnitude, not the exact unscaled value.</li>
 * </ul>
 *
 * <p>Java 8 runtime floor: no {@code List.of}/records; collections use {@code Arrays.asList} and the
 * concrete {@code java.util} implementations.
 */
public final class UltraComplexDTO {

    // ---------------------------------------------------------------------
    // Strings — escaping, emptiness, multi-line, Unicode, codec-sensitive
    // ---------------------------------------------------------------------
    @Key("string")
    @Comment("A plain string")
    public String string = "Hello World";

    @Key("empty-string")
    @Comment("Empty, distinct from null")
    public String emptyString = "";

    @Key("blank-string")
    @Comment("Only whitespace; quoting must be preserved")
    public String blankString = "   ";

    @Key("null-string")
    @Comment("Null reads back absent on a codec with no null type")
    public String nullString = null;

    @Key("multiline-string")
    @Comment("Embedded newlines (LF only; a lone CR is normalized by text engines)")
    public String multilineString = "Line 1\nLine 2\nLine 3";

    @Key("unicode-string")
    @Comment("Accents, CJK, an emoji, combining-friendly Latin")
    public String unicodeString = "Olá 世界 😀 ñ ç áéíóú";

    @Key("special-chars")
    @Comment("Punctuation, a tab, quotes and a backslash (no lone CR, which round-trips unreliably)")
    public String specialChars = "!@#$%^&*()[]{}<>|\\\"'`\n\t";

    @Key("quoted-string")
    @Comment("Both quote styles inside the value")
    public String quotedString = "\"double\" and 'single' quotes";

    @Key("yaml-sensitive")
    @Comment("Looks like a mapping and a comment to a naive YAML scanner")
    public String yamlSensitive = "yes: no # comment";

    @Key("emoji")
    @Comment("Multi-byte astral-plane characters")
    public String emoji = "🚀🔥🎉";

    @Key("very-long-string")
    @Comment("One long logical line; must not be hard-wrapped by the emitter")
    public String veryLongString =
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit. "
                    + "Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.";

    // ---------------------------------------------------------------------
    // Primitive types
    // ---------------------------------------------------------------------
    @Key("bool")
    public boolean bool = true;
    @Key("byte")
    public byte byteValue = Byte.MAX_VALUE;
    @Key("short")
    public short shortValue = Short.MAX_VALUE;
    @Key("int")
    public int intValue = Integer.MAX_VALUE;
    @Key("long")
    public long longValue = Long.MAX_VALUE;
    @Key("float")
    public float floatValue = Float.MAX_VALUE;
    @Key("double")
    public double doubleValue = Double.MAX_VALUE;
    @Key("char")
    public char charValue = 'A';

    // ---------------------------------------------------------------------
    // Integer boundaries
    // ---------------------------------------------------------------------
    @Key("min-int")
    public int minInt = Integer.MIN_VALUE;
    @Key("max-int")
    public int maxInt = Integer.MAX_VALUE;
    @Key("min-long")
    public long minLong = Long.MIN_VALUE;
    @Key("max-long")
    public long maxLong = Long.MAX_VALUE;
    @Key("zero")
    public int zero = 0;
    @Key("negative")
    public int negative = -100;

    // ---------------------------------------------------------------------
    // Floating-point edge cases (see the class caveats above)
    // ---------------------------------------------------------------------
    @Key("nan-value")
    public Double nanValue = Double.NaN;
    @Key("positive-infinity")
    public Double positiveInfinity = Double.POSITIVE_INFINITY;
    @Key("negative-infinity")
    public Double negativeInfinity = Double.NEGATIVE_INFINITY;
    @Key("negative-zero")
    public Double negativeZero = -0.0;

    // ---------------------------------------------------------------------
    // Wrapper types (one deliberately null)
    // ---------------------------------------------------------------------
    @Key("wrapper-bool")
    public Boolean wrapperBool = null;
    @Key("wrapper-int")
    public Integer wrapperInt = 123;
    @Key("wrapper-double")
    public Double wrapperDouble = 123.45;
    @Key("wrapper-char")
    public Character wrapperChar = 'Ω';

    // ---------------------------------------------------------------------
    // Big numbers
    // ---------------------------------------------------------------------
    @Key("big-decimal")
    public BigDecimal bigDecimal = new BigDecimal("999999999999999999.123456789");
    @Key("big-integer")
    public BigInteger bigInteger = new BigInteger("999999999999999999999999999");

    // ---------------------------------------------------------------------
    // Date / time (jsr310, emitted as ISO-8601 strings)
    // ---------------------------------------------------------------------
    @Key("local-date")
    public LocalDate localDate = LocalDate.of(2026, 6, 29);
    @Key("local-time")
    public LocalTime localTime = LocalTime.of(23, 59, 59);
    @Key("local-date-time")
    public LocalDateTime localDateTime = LocalDateTime.of(2026, 6, 29, 12, 30);
    @Key("offset-date-time")
    public OffsetDateTime offsetDateTime =
            OffsetDateTime.of(2026, 6, 29, 12, 30, 0, 0, ZoneOffset.ofHours(-3));
    @Key("zoned-date-time")
    public ZonedDateTime zonedDateTime =
            ZonedDateTime.of(2026, 6, 29, 12, 30, 0, 0, ZoneId.of("UTC"));
    @Key("instant")
    public Instant instant = Instant.parse("2026-06-29T12:30:00Z");
    @Key("duration")
    public Duration duration = Duration.ofHours(2);
    @Key("period")
    public Period period = Period.ofDays(30);

    // ---------------------------------------------------------------------
    // Arrays
    // ---------------------------------------------------------------------
    @Key("string-array")
    public String[] stringArray = {"one", "two", "three"};
    @Key("int-array")
    public int[] intArray = {1, 2, 3, 4};
    @Key("boolean-array")
    public boolean[] booleanArray = {true, false, true};
    @Key("empty-array")
    public String[] emptyArray = {};

    // ---------------------------------------------------------------------
    // Collections (List / Set / SortedSet / Queue / Deque, plus empty & immutable)
    // ---------------------------------------------------------------------
    @Key("string-list")
    public List<String> stringList = Arrays.asList("A", "B", "C");
    @Key("integer-list")
    public List<Integer> integerList = Arrays.asList(1, 2, 3);
    @Key("double-list")
    public List<Double> doubleList = Arrays.asList(1.1, 2.2, 3.3);
    @Key("nested-list")
    public List<List<String>> nestedList =
            Arrays.asList(Arrays.asList("A", "B"), Arrays.asList("C", "D"));
    @Key("string-set")
    public Set<String> stringSet = new LinkedHashSet<>(Arrays.asList("X", "Y"));
    @Key("sorted-set")
    public SortedSet<String> sortedSet = new TreeSet<>(Arrays.asList("C", "A", "B"));
    @Key("queue")
    public Queue<String> queue = new LinkedList<>(Arrays.asList("a", "b"));
    @Key("deque")
    public Deque<Integer> deque = new ArrayDeque<>(Arrays.asList(1, 2, 3));
    @Key("empty-list")
    public List<String> emptyList = Collections.emptyList();
    @Key("immutable-list")
    public List<String> immutableList =
            Collections.unmodifiableList(Arrays.asList("immutable1", "immutable2"));

    // ---------------------------------------------------------------------
    // Maps (flat, typed, Object-valued, deeply generic, empty)
    // ---------------------------------------------------------------------
    @Key("string-map")
    public Map<String, String> stringMap = stringMap();
    @Key("integer-map")
    public Map<String, Integer> integerMap = integerMap();
    @Key("nested-map")
    public Map<String, Object> nestedMap = nestedMap();
    @Key("deep-map")
    public Map<String, Map<String, List<Integer>>> deepMap = deepMap();
    @Key("empty-map")
    public Map<String, String> emptyMap = Collections.emptyMap();

    // ---------------------------------------------------------------------
    // Enums
    // ---------------------------------------------------------------------
    @Key("enum")
    public UltraEnum enumValue = UltraEnum.SECOND;
    @Key("enum-list")
    public List<UltraEnum> enumList = Arrays.asList(UltraEnum.FIRST, UltraEnum.THIRD);

    // ---------------------------------------------------------------------
    // Optional (present / empty / primitive specialization)
    // ---------------------------------------------------------------------
    @Key("optional-string")
    public Optional<String> optionalString = Optional.of("value");
    @Key("empty-optional")
    public Optional<String> emptyOptional = Optional.empty();
    @Key("optional-int")
    public OptionalInt optionalInt = OptionalInt.of(42);

    // ---------------------------------------------------------------------
    // Miscellaneous reflective types that generic binders often miss
    // ---------------------------------------------------------------------
    @Key("object")
    public Object object = "generic";
    @Key("null-object")
    public Object nullObject = null;
    @Key("class-type")
    public Class<?> classType = String.class;
    @Key("uuid")
    public UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    @Key("uri")
    public URI uri = URI.create("https://example.com/path?q=1");
    @Key("locale")
    public Locale locale = Locale.US;
    @Key("currency")
    public Currency currency = Currency.getInstance("USD");
    @Key("zone-id")
    public ZoneId zoneId = ZoneId.of("UTC");

    // ---------------------------------------------------------------------
    // Nested objects (single + list of)
    // ---------------------------------------------------------------------
    @Key("nested-object")
    public NestedConfig nestedObject = new NestedConfig();
    @Key("nested-object-list")
    public List<NestedConfig> nestedObjectList =
            Arrays.asList(new NestedConfig(), new NestedConfig());

    // ---------------------------------------------------------------------
    // Five-level deep nesting: a relocation + binding chain through @Key names
    // ---------------------------------------------------------------------
    @Key("deep-nesting")
    public DeepNest1 deepNesting = new DeepNest1();

    // ===================== inner types =====================

    public enum UltraEnum {
        FIRST, SECOND, THIRD
    }

    /** A small object reused as a single field and as list elements. */
    @Data
    @NoArgsConstructor
    public static class NestedConfig {
        @Key("name")
        public String name = "Nested";
        @Key("enabled")
        public boolean enabled = true;
        @Key("level")
        public int level = 1;
        @Key("nested-enum")
        public UltraEnum nestedEnum = UltraEnum.FIRST;
    }

    /** Level 1 of the five-deep chain. Every level carries scalars plus the next level. */
    @Data
    @NoArgsConstructor
    public static class DeepNest1 {
        @Key("name")
        public String name = "level-1";
        @Key("value")
        public int value = 1;
        @Key("level-2")
        public DeepNest2 level2 = new DeepNest2();
    }

    @Data
    @NoArgsConstructor
    public static class DeepNest2 {
        @Key("name")
        public String name = "level-2";
        @Key("value")
        public int value = 2;
        @Key("level-3")
        public DeepNest3 level3 = new DeepNest3();
    }

    @Data
    @NoArgsConstructor
    public static class DeepNest3 {
        @Key("name")
        public String name = "level-3";
        @Key("value")
        public int value = 3;
        @Key("level-4")
        public DeepNest4 level4 = new DeepNest4();
    }

    @Data
    @NoArgsConstructor
    public static class DeepNest4 {
        @Key("name")
        public String name = "level-4";
        @Key("value")
        public int value = 4;
        @Key("level-5")
        public DeepNest5 level5 = new DeepNest5();
    }

    /** The leaf level: no further child, carries a primitive and an enum so the deepest cell is varied. */
    @Data
    @NoArgsConstructor
    public static class DeepNest5 {
        @Key("name")
        public String name = "level-5";
        @Key("value")
        public int value = 5;
        @Key("enabled")
        public boolean enabled = true;
        @Key("mode")
        public UltraEnum mode = UltraEnum.THIRD;
    }

    // ===================== map seeds (Java-8 safe, insertion-ordered) =====================

    private static Map<String, String> stringMap() {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("first", "1");
        m.put("second", "2");
        return m;
    }

    private static Map<String, Integer> integerMap() {
        final Map<String, Integer> m = new LinkedHashMap<>();
        m.put("a", 10);
        m.put("b", 20);
        return m;
    }

    /** Mixed value types under one map to exercise an {@code Object}-valued map round trip. */
    private static Map<String, Object> nestedMap() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", "root");
        m.put("flag", Boolean.TRUE);
        m.put("count", 7);
        final Map<String, Object> child = new LinkedHashMap<>();
        child.put("inner", "leaf");
        m.put("child", child);
        return m;
    }

    /** Two levels of map nesting whose leaves are integer lists. */
    private static Map<String, Map<String, List<Integer>>> deepMap() {
        final Map<String, List<Integer>> inner = new LinkedHashMap<>();
        inner.put("evens", Arrays.asList(2, 4, 6));
        inner.put("odds", Arrays.asList(1, 3, 5));
        final Map<String, Map<String, List<Integer>>> m = new LinkedHashMap<>();
        m.put("group", inner);
        return m;
    }
}
