package br.com.finalcraft.everyconfig.config.data;

import br.com.finalcraft.everyconfig.annotation.Comment;
import br.com.finalcraft.everyconfig.annotation.Key;
import br.com.finalcraft.everyconfig.binding.introspect.KeyCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
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
 * trip end-to-end. Each field carries a {@code @Comment} naming the distinct hazard it probes — string
 * escaping, numeric boundaries, the floating-point non-finite values, {@code java.time}, every collection
 * flavor, nested generics, a deep relocation chain — so a regression in any one cell of the matrix shows up
 * as a single failed assertion, and the emitted config doubles as a self-documenting tour of the library.
 *
 * <p><b>Key naming:</b> the on-disk keys come from a class-wide {@code KEBAB_CASE} strategy
 * ({@code @JsonNaming(KeyCaseStrategy.Kebab.class)} on every class here), so a field like {@code stringList}
 * becomes {@code string-list} with no per-field annotation. An explicit {@code @Key} survives only where the
 * key cannot be derived from the field name: the abbreviated primitive keys ({@code byteValue -> byte}, ...,
 * {@code enumValue -> enum}) and the digit-suffixed deep-nest links ({@code level2 -> level-2}). Those
 * survivors also exercise per-field {@code @Key} precedence over the class strategy.
 *
 * <p><b>Codec caveats baked into the field choices (verified per codec, asserted accordingly):</b>
 * <ul>
 *   <li>The non-finite doubles ({@link #nanValue}/{@link #positiveInfinity}/{@link #negativeInfinity}) have
 *       no valid TOML representation in the current Jackson writer, so the round-trip test clears them on
 *       every codec until that is fixed upstream.</li>
 *   <li>{@link #negativeZero} loses its sign on TOML ({@code -0.0 -> 0.0}); the test compares with a delta.</li>
 *   <li>{@link #bigDecimal} keeps its full scale only on TOML — JSON-family parsers read a float as a
 *       {@code double}, collapsing the scale — so the test compares magnitude, not the exact unscaled value.</li>
 * </ul>
 *
 * <p>Java 8 runtime floor: no {@code List.of}/records; collections use {@code Arrays.asList} and the
 * concrete {@code java.util} implementations.
 */
@JsonNaming(KeyCaseStrategy.Kebab.class)
public final class UltraComplexDTO {

    // ---------------------------------------------------------------------
    // Strings — escaping, emptiness, multi-line, Unicode, codec-sensitive
    // ---------------------------------------------------------------------
    @Comment("A plain string with no characters needing special handling")
    public String string = "Hello World";

    @Comment("Empty string; must stay distinct from an absent key and from null")
    public String emptyString = "";

    @Comment("Whitespace-only value; the emitter must quote it so the spaces survive")
    public String blankString = "   ";

    @Comment("Null value; reads back absent on a codec with no null type (e.g. TOML)")
    public String nullString = null;

    @Comment("Embedded newlines (LF only; a lone CR is normalized by text engines)")
    public String multilineString = "Line 1\nLine 2\nLine 3";

    @Comment("Accents, CJK, an emoji and Latin diacritics in one value")
    public String unicodeString = "Olá 世界 😀 ñ ç áéíóú";

    @Comment("Punctuation, a tab, both quote styles and a backslash (no lone CR, which round-trips unreliably)")
    public String specialChars = "!@#$%^&*()[]{}<>|\\\"'`\n\t";

    @Comment("Both quote styles inside the value, to exercise the codec's escaping")
    public String quotedString = "\"double\" and 'single' quotes";

    @Comment("Looks like a mapping plus a comment to a naive YAML scanner, but is plain text")
    public String yamlSensitive = "yes: no # comment";

    @Comment("Astral-plane (multi-byte) characters only")
    public String emoji = "🚀🔥🎉";

    @Comment("One long logical line; the emitter must not hard-wrap it")
    public String veryLongString =
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit. "
                    + "Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.";

    // ---------------------------------------------------------------------
    // Primitive types — one value per primitive family (abbreviated keys
    // kept as explicit @Key, since KEBAB_CASE cannot shorten the field name)
    // ---------------------------------------------------------------------
    @Comment("Primitive boolean")
    public boolean bool = true;

    @Key("byte")
    @Comment("Primitive byte at its maximum (127)")
    public byte byteValue = Byte.MAX_VALUE;

    @Key("short")
    @Comment("Primitive short at its maximum (32767)")
    public short shortValue = Short.MAX_VALUE;

    @Key("int")
    @Comment("Primitive int at its maximum")
    public int intValue = Integer.MAX_VALUE;

    @Key("long")
    @Comment("Primitive long at its maximum; TOML stores it quoted to avoid reader overflow")
    public long longValue = Long.MAX_VALUE;

    @Key("float")
    @Comment("Primitive float at its maximum; widens to double in the tree")
    public float floatValue = Float.MAX_VALUE;

    @Key("double")
    @Comment("Primitive double at its maximum")
    public double doubleValue = Double.MAX_VALUE;

    @Key("char")
    @Comment("Primitive char; serialized as a single-character string")
    public char charValue = 'A';

    // ---------------------------------------------------------------------
    // Integer boundaries — the extremes that catch off-by-one width bugs
    // ---------------------------------------------------------------------
    @Comment("Integer.MIN_VALUE")
    public int minInt = Integer.MIN_VALUE;

    @Comment("Integer.MAX_VALUE")
    public int maxInt = Integer.MAX_VALUE;

    @Comment("Long.MIN_VALUE")
    public long minLong = Long.MIN_VALUE;

    @Comment("Long.MAX_VALUE")
    public long maxLong = Long.MAX_VALUE;

    @Comment("Plain zero, the identity boundary")
    public int zero = 0;

    @Comment("A negative value, to exercise the sign")
    public int negative = -100;

    // ---------------------------------------------------------------------
    // Floating-point edge cases (see the class caveats above)
    // ---------------------------------------------------------------------
    @Comment("Not-a-number; no valid TOML literal in the current Jackson writer")
    public Double nanValue = Double.NaN;

    @Comment("Positive infinity; same TOML-writer limitation as NaN")
    public Double positiveInfinity = Double.POSITIVE_INFINITY;

    @Comment("Negative infinity; same TOML-writer limitation as NaN")
    public Double negativeInfinity = Double.NEGATIVE_INFINITY;

    @Comment("Negative zero; its sign is lost on TOML (-0.0 -> 0.0)")
    public Double negativeZero = -0.0;

    // ---------------------------------------------------------------------
    // Wrapper types — boxing plus one deliberately null
    // ---------------------------------------------------------------------
    @Comment("Boxed Boolean left null, to prove a null wrapper survives as absent/null")
    public Boolean wrapperBool = null;

    @Comment("Boxed Integer with a normal value")
    public Integer wrapperInt = 123;

    @Comment("Boxed Double with a fractional value")
    public Double wrapperDouble = 123.45;

    @Comment("Boxed Character holding a non-ASCII letter")
    public Character wrapperChar = 'Ω';

    // ---------------------------------------------------------------------
    // Big numbers — beyond the 64-bit range / decimal precision frontier
    // ---------------------------------------------------------------------
    @Comment("High-scale decimal; full scale survives only on TOML, magnitude survives everywhere")
    public BigDecimal bigDecimal = new BigDecimal("999999999999999999.123456789");

    @Comment("Integer beyond 64 bits; round-trips exactly on every codec")
    public BigInteger bigInteger = new BigInteger("999999999999999999999999999");

    // ---------------------------------------------------------------------
    // Date / time (jsr310) — emitted as ISO-8601 strings
    // ---------------------------------------------------------------------
    @Comment("Date with no time or zone")
    public LocalDate localDate = LocalDate.of(2026, 6, 29);

    @Comment("Time of day with no date or zone")
    public LocalTime localTime = LocalTime.of(23, 59, 59);

    @Comment("Date and time with no zone")
    public LocalDateTime localDateTime = LocalDateTime.of(2026, 6, 29, 12, 30);

    @Comment("Instant with a fixed UTC offset")
    public OffsetDateTime offsetDateTime =
            OffsetDateTime.of(2026, 6, 29, 12, 30, 0, 0, ZoneOffset.ofHours(-3));

    @Comment("Instant with a named time zone")
    public ZonedDateTime zonedDateTime =
            ZonedDateTime.of(2026, 6, 29, 12, 30, 0, 0, ZoneId.of("UTC"));

    @Comment("A point on the UTC timeline")
    public Instant instant = Instant.parse("2026-06-29T12:30:00Z");

    @Comment("An amount of time, ISO-8601 duration form (PT2H)")
    public Duration duration = Duration.ofHours(2);

    @Comment("A date-based amount, ISO-8601 period form (P30D)")
    public Period period = Period.ofDays(30);

    // ---------------------------------------------------------------------
    // Arrays — primitive, reference, and empty
    // ---------------------------------------------------------------------
    @Comment("Reference-type array; binds back into a String[] field")
    public String[] stringArray = {"one", "two", "three"};

    @Comment("Primitive int array")
    public int[] intArray = {1, 2, 3, 4};

    @Comment("Primitive boolean array")
    public boolean[] booleanArray = {true, false, true};

    @Comment("Empty array; must round-trip as an empty sequence, not null")
    public String[] emptyArray = {};

    // ---------------------------------------------------------------------
    // Collections — List / Set / SortedSet / Queue / Deque, plus empty & immutable
    // ---------------------------------------------------------------------
    @Comment("Ordered list of strings")
    public List<String> stringList = Arrays.asList("A", "B", "C");

    @Comment("Ordered list of integers")
    public List<Integer> integerList = Arrays.asList(1, 2, 3);

    @Comment("Ordered list of doubles, checking fractional round-trip")
    public List<Double> doubleList = Arrays.asList(1.1, 2.2, 3.3);

    @Comment("List of lists, an array of arrays in the codec")
    public List<List<String>> nestedList =
            Arrays.asList(Arrays.asList("A", "B"), Arrays.asList("C", "D"));

    @Comment("Set preserving insertion order (LinkedHashSet)")
    public Set<String> stringSet = new LinkedHashSet<>(Arrays.asList("X", "Y"));

    @Comment("Set whose elements come back in sorted order (TreeSet)")
    public SortedSet<String> sortedSet = new TreeSet<>(Arrays.asList("C", "A", "B"));

    @Comment("FIFO queue; binds back to a Queue implementation")
    public Queue<String> queue = new LinkedList<>(Arrays.asList("a", "b"));

    @Comment("Double-ended queue of integers")
    public Deque<Integer> deque = new ArrayDeque<>(Arrays.asList(1, 2, 3));

    @Comment("Empty list, the collection counterpart of empty-array")
    public List<String> emptyList = Collections.emptyList();

    @Comment("Unmodifiable list; must serialize like any list and bind back mutable")
    public List<String> immutableList =
            Collections.unmodifiableList(Arrays.asList("immutable1", "immutable2"));

    // ---------------------------------------------------------------------
    // Maps — flat, typed, Object-valued, deeply generic, empty
    // ---------------------------------------------------------------------
    @Comment("String-to-string map; insertion order is preserved")
    public Map<String, String> stringMap = stringMap();

    @Comment("String-to-integer map")
    public Map<String, Integer> integerMap = integerMap();

    @Comment("Object-valued map mixing a string, a boolean, a number and a nested map")
    public Map<String, Object> nestedMap = nestedMap();

    @Comment("Two levels of map nesting whose leaves are integer lists")
    public Map<String, Map<String, List<Integer>>> deepMap = deepMap();

    @Comment("Empty map; must round-trip as an empty table, not null")
    public Map<String, String> emptyMap = Collections.emptyMap();

    // ---------------------------------------------------------------------
    // Enums — single value and a list
    // ---------------------------------------------------------------------
    @Key("enum")
    @Comment("Enum serialized by its name")
    public UltraEnum enumValue = UltraEnum.SECOND;

    @Comment("List of enum constants, each by name")
    public List<UltraEnum> enumList = Arrays.asList(UltraEnum.FIRST, UltraEnum.THIRD);

    // ---------------------------------------------------------------------
    // Optional — present, empty, and the int specialization
    // ---------------------------------------------------------------------
    @Comment("Present Optional; unwraps to its value")
    public Optional<String> optionalString = Optional.of("value");

    @Comment("Empty Optional; serializes as null/absent and reads back empty")
    public Optional<String> emptyOptional = Optional.empty();

    @Comment("OptionalInt primitive specialization, present")
    public OptionalInt optionalInt = OptionalInt.of(42);

    // ---------------------------------------------------------------------
    // Miscellaneous reflective types that generic binders often miss
    // ---------------------------------------------------------------------
    @Comment("Statically-typed Object holding a string; binds back to its runtime type")
    public Object object = "generic";

    @Comment("Statically-typed Object left null")
    public Object nullObject = null;

    @Comment("A java.lang.Class; serialized as its fully-qualified name")
    public Class<?> classType = String.class;

    @Comment("UUID, serialized as its canonical string")
    public UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Comment("URI with a query string")
    public URI uri = URI.create("https://example.com/path?q=1");

    @Comment("Locale, serialized as a language tag")
    public Locale locale = Locale.US;

    @Comment("Currency, serialized as its ISO-4217 code")
    public Currency currency = Currency.getInstance("USD");

    @Comment("Time-zone identifier")
    public ZoneId zoneId = ZoneId.of("UTC");

    // ---------------------------------------------------------------------
    // Nested objects — a single object and a list of them
    // ---------------------------------------------------------------------
    @Comment("A nested POJO with its own scalar fields and enum")
    public NestedConfig nestedObject = new NestedConfig();

    @Comment("List of nested POJOs; an array-of-tables on TOML")
    public List<NestedConfig> nestedObjectList =
            Arrays.asList(new NestedConfig(), new NestedConfig());

    // ---------------------------------------------------------------------
    // Five-level deep nesting — a binding + @Key relocation chain
    // ---------------------------------------------------------------------
    @Comment("Entry point of a five-level-deep nested-object chain")
    public DeepNest1 deepNesting = new DeepNest1();

    // ===================== inner types =====================

    public enum UltraEnum {
        FIRST, SECOND, THIRD
    }

    /** A small object reused as a single field and as list elements. */
    @Data
    @NoArgsConstructor
    @JsonNaming(KeyCaseStrategy.Kebab.class)
    public static class NestedConfig {
        @Comment("Display name of this nested object")
        public String name = "Nested";

        @Comment("Whether this nested object is enabled")
        public boolean enabled = true;

        @Comment("An arbitrary depth/level counter")
        public int level = 1;

        @Comment("An enum living inside the nested object")
        public UltraEnum nestedEnum = UltraEnum.FIRST;
    }

    /** Level 1 of the five-deep chain. Every level carries scalars plus the next level. */
    @Data
    @NoArgsConstructor
    @JsonNaming(KeyCaseStrategy.Kebab.class)
    public static class DeepNest1 {
        @Comment("Marker for level 1")
        public String name = "level-1";

        @Comment("Numeric marker for level 1")
        public int value = 1;

        @Key("level-2")
        @Comment("Link to level 2")
        public DeepNest2 level2 = new DeepNest2();
    }

    @Data
    @NoArgsConstructor
    @JsonNaming(KeyCaseStrategy.Kebab.class)
    public static class DeepNest2 {
        @Comment("Marker for level 2")
        public String name = "level-2";

        @Comment("Numeric marker for level 2")
        public int value = 2;

        @Key("level-3")
        @Comment("Link to level 3")
        public DeepNest3 level3 = new DeepNest3();
    }

    @Data
    @NoArgsConstructor
    @JsonNaming(KeyCaseStrategy.Kebab.class)
    public static class DeepNest3 {
        @Comment("Marker for level 3")
        public String name = "level-3";

        @Comment("Numeric marker for level 3")
        public int value = 3;

        @Key("level-4")
        @Comment("Link to level 4")
        public DeepNest4 level4 = new DeepNest4();
    }

    @Data
    @NoArgsConstructor
    @JsonNaming(KeyCaseStrategy.Kebab.class)
    public static class DeepNest4 {
        @Comment("Marker for level 4")
        public String name = "level-4";

        @Comment("Numeric marker for level 4")
        public int value = 4;

        @Key("level-5")
        @Comment("Link to the deepest level")
        public DeepNest5 level5 = new DeepNest5();
    }

    /** The leaf level: no further child, carries a primitive and an enum so the deepest cell is varied. */
    @Data
    @NoArgsConstructor
    @JsonNaming(KeyCaseStrategy.Kebab.class)
    public static class DeepNest5 {
        @Comment("Marker for the deepest level")
        public String name = "level-5";

        @Comment("Numeric marker asserted through the full dotted path")
        public int value = 5;

        @Comment("A boolean at the deepest level")
        public boolean enabled = true;

        @Comment("An enum at the deepest level")
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
