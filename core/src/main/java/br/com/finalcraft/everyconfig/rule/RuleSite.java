package br.com.finalcraft.everyconfig.rule;

import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One declared rule at one addressable location of an entity type: the annotation instance plus everything
 * needed to report it or to build a screen from it — where its value lives in the FILE, what the field
 * documents, and what a freshly constructed entity holds there.
 *
 * <p>Immutable and resolved by the library; a consumer holds and reads a site, it never builds one. The
 * only mutable part is the memoized {@link #defaultValue()}, which is idempotent.
 */
public final class RuleSite {

    /** What the rule is attached to. */
    public enum Kind {

        /** A field: {@link #path()} is where its value lands in the file. */
        FIELD,

        /** A type: the rule is an invariant of the entity, reported at the entity's own path. */
        TYPE,

        /** A no-argument method: the value is what it returns, reported at the entity's own path. */
        METHOD
    }

    /** Marks a resolution that produced nothing, so a negative result is computed once and remembered. */
    private static final Object ABSENT = new Object();

    /** One default instance per type, built lazily: resolving a default value must not re-run a constructor
     *  (and its side effects) on every call. A type that cannot be built without data caches {@link #ABSENT}. */
    private static final ConcurrentHashMap<Class<?>, Object> DEFAULT_INSTANCES = new ConcurrentHashMap<>();

    private final Kind kind;
    private final String path;
    private final Annotation rule;
    private final Class<?> valueType;
    private final Field field;
    private final Method method;
    private final Class<?> owner;
    private final List<String> comment;

    /** The type the sites were resolved for — the root the field chain is walked from. */
    private final Class<?> entryType;

    /** The fields from the resolved type down to the instance that declares this site, in order; empty for a
     *  site on the type itself. Not public: it exists so the bind can reach the owning instance of a nested
     *  site, which is an application concern, not something a consumer should navigate. */
    private final List<Field> ownerChain;

    private volatile Object resolvedDefault;

    RuleSite(final Kind kind, final String path, final Annotation rule, final Class<?> valueType,
             final Field field, final Method method, final Class<?> owner, final List<String> comment,
             final Class<?> entryType, final List<Field> ownerChain) {
        this.kind = kind;
        this.path = path;
        this.rule = rule;
        this.valueType = valueType;
        this.field = field;
        this.method = method;
        this.owner = owner;
        this.comment = comment;
        this.entryType = entryType;
        this.ownerChain = ownerChain;
    }

    public Kind kind() {
        return kind;
    }

    /** The dotted FILE path of the value: {@code @Key}/{@code @JsonProperty}/case transform and the
     *  {@code @Section} spine already applied. A TYPE or METHOD site reports the entity's own path
     *  ({@code ""} for the top-level entity), because neither has a key of its own. */
    public String path() {
        return path;
    }

    /** The declared annotation instance, typed attributes and all. */
    public Annotation rule() {
        return rule;
    }

    /** FIELD: the declared raw type. TYPE: the class itself. METHOD: the return type. */
    public Class<?> valueType() {
        return valueType;
    }

    /** The field, for a FIELD site — the gateway to generics via {@code getGenericType()}; null otherwise. */
    @Nullable
    public Field field() {
        return field;
    }

    /** The method, for a METHOD site (no-argument, non-static); null otherwise. */
    @Nullable
    public Method method() {
        return method;
    }

    /** The class declaring the annotated member — for a TYPE site, the annotated class itself. */
    public Class<?> owner() {
        return owner;
    }

    /** The {@code @Comment} lines declared at this site (on the field, the class or the method); empty when
     *  there are none. */
    public List<String> comment() {
        return comment;
    }

    /**
     * What a freshly constructed entity holds (FIELD), is (TYPE) or returns (METHOD) here — the default a
     * screen offers as the reset value. Null when the entity cannot be built without data, when an
     * intermediate owner is null, or when the value simply IS null.
     *
     * <p>Resolved on first call and remembered, negatives included, because constructing an entity may have
     * side effects. The returned object is the LIVE default instance's value, shared with every caller:
     * read it, never mutate it. Reflection only — no config, no mapper, no file.
     */
    @Nullable
    public Object defaultValue() {
        Object resolved = resolvedDefault;
        if (resolved == null) {
            synchronized (this) {
                resolved = resolvedDefault;
                if (resolved == null) {
                    resolved = resolveDefaultValue();
                    resolvedDefault = resolved;
                }
            }
        }
        return resolved == ABSENT ? null : resolved;
    }

    /** The fields to walk from the resolved type to this site's owning instance; empty at the top level. */
    List<Field> ownerChain() {
        return ownerChain;
    }

    private Object resolveDefaultValue() {
        Object instance = defaultInstanceOf(entryType);
        for (final Field link : ownerChain) {
            if (instance == ABSENT || instance == null) {
                return ABSENT;
            }
            instance = read(link, instance);
        }
        if (instance == ABSENT || instance == null) {
            return ABSENT;
        }
        if (kind == Kind.FIELD) {
            final Object value = read(field, instance);
            return value == null ? ABSENT : value;
        }
        if (kind == Kind.METHOD) {
            final Object value = invoke(method, instance);
            return value == null ? ABSENT : value;
        }
        return instance;
    }

    private static Object read(final Field target, final Object instance) {
        try {
            target.setAccessible(true);
            return target.get(instance);
        } catch (final Exception unreadable) {
            return null;
        }
    }

    /** A method that throws while producing a DEFAULT yields no default — introspection must not fail
     *  because an entity's computed invariant is unhappy about an empty instance. */
    private static Object invoke(final Method target, final Object instance) {
        try {
            target.setAccessible(true);
            return target.invoke(instance);
        } catch (final Exception uncallable) {
            return null;
        }
    }

    private static Object defaultInstanceOf(final Class<?> type) {
        return DEFAULT_INSTANCES.computeIfAbsent(type, RuleSite::newDefaultInstance);
    }

    private static Object newDefaultInstance(final Class<?> type) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            return ABSENT;
        }
        try {
            final Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (final Exception notConstructible) {
            return ABSENT;
        }
    }

    @Override
    public String toString() {
        return kind + " @" + rule.annotationType().getSimpleName() + " at '" + path + "' on "
                + owner.getSimpleName();
    }
}
