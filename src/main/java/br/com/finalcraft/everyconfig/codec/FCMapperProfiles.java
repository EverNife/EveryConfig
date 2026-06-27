package br.com.finalcraft.everyconfig.codec;

import br.com.finalcraft.everyconfig.binding.introspect.FinalConfigModule;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.function.Supplier;

/**
 * Named Jackson profiles for FinalConfig codecs, expressed as composable presets rather than loose
 * flags. Each method mutates and returns the given mapper, composing over JSON/YAML alike (a
 * {@code YAMLMapper} extends {@code ObjectMapper}); the {@code <M extends ObjectMapper>} generic
 * preserves the concrete mapper type. Mutation happens once at construction, so the result is safe for
 * concurrent use afterwards.
 *
 * <p>{@code MapperFeature}-based knobs (e.g. alphabetical property sorting) are deliberately omitted:
 * mutating them on an already-built mapper is deprecated. Map-entry ordering below is a
 * {@code SerializationFeature}, so it is safe to set this way.
 */
public final class FCMapperProfiles {

    /**
     * The {@code Jdk8Module} (Optional support) is registered reflectively because
     * jackson-datatype-jdk8 is an optional dependency: it is wired only when present on the classpath,
     * and its absence simply means {@code Optional} fields are not specially handled.
     */
    private static final Module JDK8_MODULE = loadJdk8ModuleOrNull();

    private FCMapperProfiles() {
    }

    /**
     * Frozen read contract every profile builds on: java.time support, optional Optional support, and
     * tolerance of unknown properties (so unknown keys survive into the tree). Because the contract reads
     * both epoch and ISO-8601 dates, any profile can read what any other profile wrote.
     *
     * <p>Map-entry ordering is deliberately left at Jackson's default (insertion order, not alphabetical):
     * key order is owned by the tree and the comment reconciler, so the mapper must not re-sort entries
     * underneath it. A {@code Map} field therefore round-trips in its insertion order.
     */
    public static <M extends ObjectMapper> M baseReadContract(final M mapper) {
        mapper.registerModule(new JavaTimeModule());
        if (JDK8_MODULE != null) {
            mapper.registerModule(JDK8_MODULE);
        }
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    /**
     * Default profile: round-trip fidelity and schema-evolution tolerance. ISO-8601 dates/durations
     * (portable, human-readable), null properties KEPT. This is the mapper backing the default codecs.
     * Nulls are kept because an explicit {@code null} in a config file is user data and must survive;
     * dropping nulls is the {@link #compact} opt-in.
     */
    public static <M extends ObjectMapper> M storageSafe(final M mapper) {
        baseReadContract(mapper);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
        mapper.registerModule(new FinalConfigModule()); // key-naming annotations + enum-by-name
        return mapper;
    }

    /**
     * Space-saving profile: identical dates and ordering to {@link #storageSafe}, but drops null /
     * absent-Optional properties ({@code NON_ABSENT}). Fully interchange-compatible with storageSafe
     * (omitted -&gt; null on read), so a file can switch profiles with no migration. Opt-in only —
     * never the codec default, because a tree-preserving config must keep explicit nulls.
     */
    public static <M extends ObjectMapper> M compact(final M mapper) {
        storageSafe(mapper);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_ABSENT);
        return mapper;
    }

    /**
     * Strict-JSON profile: the {@link #storageSafe} read contract plus pretty-printed output, with no
     * comment or trailing-comma leniency enabled — the interoperable, machine-readable default for the
     * JSON codec. Config files are human-opened, hence indented.
     */
    public static <M extends ObjectMapper> M strictJson(final M mapper) {
        storageSafe(mapper);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }

    /**
     * Returns an instance the codec can own outright: an ISOLATED {@code copy()} of a user-supplied
     * mapper, or the default built by {@code defaultFactory} when no user mapper is given. The copy
     * keeps the per-codec shared instance from being corrupted by a later external mutation of the
     * caller's reference (which would otherwise change serialization for every live config of that
     * format). The copy is a one-time cost at the codec's rare, long-lived construction.
     */
    public static ObjectMapper isolate(final ObjectMapper userOrNull,
                                       final Supplier<ObjectMapper> defaultFactory) {
        return userOrNull != null ? userOrNull.copy() : defaultFactory.get();
    }

    private static Module loadJdk8ModuleOrNull() {
        try {
            final Class<?> moduleClass =
                    Class.forName("com.fasterxml.jackson.datatype.jdk8.Jdk8Module");
            return (Module) moduleClass.getDeclaredConstructor().newInstance();
        } catch (final Throwable ignored) {
            return null; // jackson-datatype-jdk8 not on the classpath; Optional support is skipped
        }
    }
}
