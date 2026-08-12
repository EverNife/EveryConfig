package br.com.finalcraft.everyconfig.binding.merge;

import br.com.finalcraft.everyconfig.annotation.Section;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.binding.merge.LifecycleInvoker.Phase;
import br.com.finalcraft.everyconfig.binding.schema.BindingNames;
import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.section.ConfigSection;
import br.com.finalcraft.everyconfig.core.coerce.TypeFamily;
import br.com.finalcraft.everyconfig.core.tree.DPath;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Fires lifecycle hooks ({@link LifecycleInvoker}) for the hook-bearing entities reachable WITHIN a bound
 * value — the descendants that the raw Jackson mapper (de)serializes and therefore never fires. The
 * top-level entity is fired by {@code EntityBinder}/{@code Config}; this walker covers everything nested:
 * POJO fields, {@code Map} values, and {@code List}/{@code Set}/array elements, at any depth. Each hook
 * receives a {@link ConfigSection} at the value's real sub-path, so a nested {@code postSave}/{@code postLoad}
 * can reach its own slice of the tree just as a top-level one can.
 *
 * <p>Sub-path grammar mirrors how the value lands in the tree: a field is its owner path plus the field's
 * on-disk key ({@code @Key}/{@code @Section}-aware); a {@code Map} value is {@code owner.<key>}; a collection
 * or array element is {@code owner[i]}. The {@code @KeyIndex}/compact-element collection layouts are a
 * top-level dynamic-collection concern (a nested {@code List<T>} field serializes plain via the mapper), so
 * they live only in the {@code Config} seam ({@link #fireCollectionElements}/{@link #warnCompactHooks}), not
 * in the graph descent.
 *
 * <p>The walk stops at a value the config's {@link ObjectMapper} does NOT serialize as an object of fields —
 * a type a Jackson module owns, written as one scalar node. Such a value has no sub-tree, hence no sub-path
 * for anything inside it: its fields are not descended and its own hooks do not fire (they are warned about
 * once). {@code Map}/{@code Collection}/array values are walked structurally regardless of serializer, since
 * their elements are what the walk is made of; a container type replaced wholesale by a custom serializer is
 * therefore outside this rule and still descended.
 *
 * <p>Each instance fires at most once per walk (an {@link IdentityHashMap}-backed visited set), so a value
 * reachable by two paths is not double-fired and a cycle terminates. {@code PRE_LOAD} is deliberately NOT
 * walked: a nested instance does not exist before its own bind, so there is no pre-load moment for it — only
 * {@code POST_LOAD}/{@code PRE_SAVE}/{@code POST_SAVE} compose in nested position.
 */
public final class LifecycleGraphWalker {

    private static final Logger LOG = Logger.getLogger(LifecycleGraphWalker.class.getName());

    /** Whether a type's graph could reach a hook-bearing instance, resolved once per class. Conservative:
     *  when in doubt it says {@code true} (walk), so a false negative — which would resurrect the silent skip
     *  this walker exists to fix — is impossible. */
    private static final ConcurrentHashMap<Class<?>, Boolean> MAY_CONTAIN = new ConcurrentHashMap<>();

    /** The same answer refined by a mapper, which can prove a type writes no field sub-tree. Keyed by mapper
     *  first, because two mappers can disagree about the same class. */
    private static final ConcurrentHashMap<ObjectMapper, ConcurrentHashMap<Class<?>, Boolean>> MAY_CONTAIN_FOR_MAPPER =
            new ConcurrentHashMap<>();

    /** Types already warned about being serialized as a compact element while carrying hooks — the warning
     *  is emitted once per type, not once per element/save. */
    private static final Set<Class<?>> WARNED_COMPACT =
            Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());

    /** Types already warned about carrying hooks while the mapper writes them as one scalar node. */
    private static final Set<Class<?>> WARNED_OPAQUE =
            Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());

    private final Config config;
    private final ObjectMapper mapper;
    private final Phase phase;
    private final List<LoadIssue> issues;
    private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());

    /** {@code mapper} decides each type's serialized shape for this walk; null (no codec) proves nothing,
     *  so the walk descends as it always did. */
    private LifecycleGraphWalker(final Config config, final ObjectMapper mapper, final Phase phase,
                                 final List<LoadIssue> issues) {
        this.config = config;
        this.mapper = mapper;
        this.phase = phase;
        this.issues = issues;
    }

    /** The shape-deciding mapper of a config-owned walk: the one the config binds with, or null for a
     *  Config with no codec. */
    private static ObjectMapper mapperOf(final Config config) {
        final Codec codec = config != null ? config.getCodec() : null;
        return codec != null ? codec.getObjectMapper() : null;
    }

    // ==================== entity read/write: descendants of an already-fired root ====================

    /**
     * Fire {@code phase} for every hook-bearing DESCENDANT of {@code root} (fields, {@code Map} values,
     * collection/array elements, recursively), each with a section at its sub-path under {@code rootPath}.
     * {@code root} itself is NOT fired — its caller ({@code EntityBinder}) already did. {@code mapper} is
     * the one that projected/bound {@code root} (the binder's, which may differ from the config's own codec
     * on a {@code bind(type, codec)} call), so shape decisions match what was actually serialized.
     */
    public static void fireDescendants(final Config config, final ObjectMapper mapper, final Object root,
                                       final String rootPath, final Phase phase, final List<LoadIssue> issues) {
        if (root == null) {
            return;
        }
        final LifecycleGraphWalker w = new LifecycleGraphWalker(config, mapper, phase, issues);
        w.visited.add(root); // the root is fired by the caller; guard it so a cycle back to it is skipped
        w.descend(root, root.getClass(), rootPath == null ? "" : rootPath);
    }

    // ==================== top-level dynamic collection (Config.readList / writeValue) ====================

    /**
     * Fire {@code phase} for each element of a top-level collection at {@code basePath} (and each element's
     * descendants). The element section is {@code basePath[i]} for a plain collection, or
     * {@code basePath.<idValue>} when {@code keyIndexed} (the key-major {@code @KeyIndex} layout). Elements are
     * fired in iteration order; the id is read afresh so a {@code PRE_SAVE} that changes it does not desync
     * the {@code POST_SAVE} path.
     */
    public static void fireCollectionElements(final Config config, final String basePath,
                                              final Collection<?> collection, final boolean keyIndexed,
                                              final Phase phase, final List<LoadIssue> issues) {
        final LifecycleGraphWalker w = new LifecycleGraphWalker(config, mapperOf(config), phase, issues);
        int i = 0;
        for (final Object element : collection) {
            final String path = keyIndexed ? keyIndexedPath(basePath, element) : indexPath(basePath, i);
            w.visit(element, path);
            i++;
        }
    }

    /** Fire {@code phase} for each value of a top-level map at {@code basePath} (value section {@code
     *  basePath.<key>}), plus each value's descendants. */
    public static void fireMapValues(final Config config, final String basePath, final Map<?, ?> map,
                                     final Phase phase, final List<LoadIssue> issues) {
        final LifecycleGraphWalker w = new LifecycleGraphWalker(config, mapperOf(config), phase, issues);
        for (final Map.Entry<?, ?> e : map.entrySet()) {
            w.visit(e.getValue(), DPath.joinSegment(basePath, String.valueOf(e.getKey())));
        }
    }

    /** Whether any element carries (or may transitively reach) hooks — the cheap gate the {@code Config} seam
     *  uses so a scalar collection/map skips the firing machinery entirely. {@code mapper} is the one the
     *  values will be serialized with (nullable; see {@link #mayContainHooks(Class, ObjectMapper)}). */
    public static boolean anyMayHaveHooks(final Iterable<?> values, final ObjectMapper mapper) {
        for (final Object v : values) {
            if (v != null && mayContainHooks(v.getClass(), mapper)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Warn once that {@code elementType} carries lifecycle hooks yet is being serialized as a compact
     * element (one string, no sub-tree) — so its hooks cannot compose and its {@code section()} would have
     * nothing to point at. Trades the silent skip for a visible signal.
     */
    public static void warnCompactHooks(final Class<?> elementType) {
        if (elementType != null && LifecycleInvoker.hasHooks(elementType) && WARNED_COMPACT.add(elementType)) {
            LOG.warning("lifecycle hooks of " + elementType.getName() + " do not fire in compact-element form "
                    + "(no sub-path to bind a ConfigSection to); persist it by path/field/Map/list, not as a "
                    + "compact list element, for its hooks to compose");
        }
    }

    /**
     * Warn once that {@code type} declares lifecycle hooks the walk will not fire: the config's mapper
     * resolves a serializer of its own for the type instead of writing it as an object of fields, so the
     * value occupies one node with no sub-path for a hook's {@link ConfigSection} to point at.
     */
    private static void warnOpaqueHooks(final Class<?> type) {
        if (LifecycleInvoker.hasHooks(type) && WARNED_OPAQUE.add(type)) {
            LOG.warning("lifecycle hooks of " + type.getName() + " do not fire: the config's ObjectMapper "
                    + "serializes this type with a serializer of its own rather than as an object of fields, "
                    + "so its value has no sub-path to bind a ConfigSection to; either drop that serializer "
                    + "so the type is written as fields, or move the hooks to the type that OWNS this value, "
                    + "whose own sub-path does exist");
        }
    }

    // ==================== the walk ====================

    /** Fire {@code value} if it is hook-bearing, then descend into it. A node already visited (cycle or
     *  shared reference) is skipped, so each instance fires at most once per walk. */
    private void visit(final Object value, final String path) {
        if (value == null || !visited.add(value)) {
            return;
        }
        final Class<?> c = value.getClass();
        if (writesNoFieldSubTree(c)) {
            warnOpaqueHooks(c);
            return;
        }
        if (LifecycleInvoker.hasHooks(c)) {
            LifecycleInvoker.fire(value, phase, new ConfigSection(config, path), issues);
        }
        descend(value, c, path);
    }

    /**
     * Whether the walk stops at {@code c}: a user POJO this walk's mapper serializes as something other than
     * an object of fields writes no sub-tree, so there is no sub-path under it to fire or descend into. The
     * class here is the value's RUNTIME class, which is exactly what the mapper will be handed, so no
     * {@code final} check is needed — unlike the static gate, which only knows a declared type.
     */
    private boolean writesNoFieldSubTree(final Class<?> c) {
        return TypeFamily.isUserPojoType(c) && !SerializedShape.emitsAsBean(mapper, c);
    }

    private void descend(final Object value, final Class<?> c, final String path) {
        if (value instanceof Map) {
            for (final Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                visit(e.getValue(), DPath.joinSegment(path, String.valueOf(e.getKey())));
            }
        } else if (value instanceof Collection) {
            int i = 0;
            for (final Object element : (Collection<?>) value) {
                visit(element, indexPath(path, i));
                i++;
            }
        } else if (c.isArray()) {
            final int n = Array.getLength(value);
            for (int i = 0; i < n; i++) {
                visit(Array.get(value, i), indexPath(path, i));
            }
        } else if (TypeFamily.isUserPojoType(c) && !writesNoFieldSubTree(c)) {
            descendFields(value, c, path);
        }
        // anything else (scalar/enum/JDK leaf) has no children the mapper serialized as a sub-tree
    }

    /** Descend into the serialized fields of a user POJO. Fields the mapper does not emit (static/transient/
     *  synthetic/{@code @JsonIgnore}) are skipped so a manually-managed field — e.g. one a {@code postLoad}
     *  reconstructs — is not fired at a path that does not exist in the tree. */
    private void descendFields(final Object value, final Class<?> c, final String path) {
        for (final Field f : BindingNames.allFields(c)) {
            if (isSkippedField(f)) {
                continue;
            }
            final Object child = readField(f, value);
            if (child != null) {
                visit(child, DPath.join(path, fieldRelativePath(f)));
            }
        }
    }

    private static boolean isSkippedField(final Field f) {
        final int mods = f.getModifiers();
        if (Modifier.isStatic(mods) || Modifier.isTransient(mods) || f.isSynthetic()) {
            return true;
        }
        final JsonIgnore ji = f.getAnnotation(JsonIgnore.class);
        return ji != null && ji.value();
    }

    private static Object readField(final Field f, final Object owner) {
        try {
            f.setAccessible(true);
            return f.get(owner);
        } catch (final Exception e) {
            return null; // an unreadable field simply contributes no descendant
        }
    }

    /** The field's path fragment within its owner: its on-disk key, prefixed by its {@code @Section} nesting
     *  when present — matching where {@code EntityBinder} relocates the value on write. */
    private static String fieldRelativePath(final Field f) {
        final String key = BindingNames.keyFor(f);
        final Section s = f.getAnnotation(Section.class);
        if (s != null && !s.value().isEmpty()) {
            String rel = "";
            for (final String seg : DPath.split(s.value())) { // @Section spells nesting with '.'
                rel = DPath.joinSegment(rel, seg);
            }
            return DPath.joinSegment(rel, key);
        }
        return DPath.escapeSegment(key);
    }

    private static String indexPath(final String base, final int i) {
        return base + "[" + i + "]"; // the bracket grammar DPath.parse resolves against an ArrayNode
    }

    private static String keyIndexedPath(final String base, final Object element) {
        try {
            final Field id = BindingNames.requireSingleKeyIndex(element.getClass());
            id.setAccessible(true);
            final Object idValue = id.get(element);
            if (idValue != null) {
                return DPath.joinSegment(base, String.valueOf(idValue));
            }
        } catch (final Exception ignored) {
            // fall back to an index path if the id cannot be read (keeps the walk from throwing on a save)
        }
        return base;
    }

    // ==================== the static gate ====================

    /**
     * Whether reading/writing an instance of {@code type} could reach a hook-bearing entity — the gate that
     * lets a flat, hook-free config skip the walk entirely. Biased hard toward {@code true}: it returns
     * {@code false} only for a type it can PROVE is hook-free (itself and every field it would descend into
     * bottoms out in JDK/enum/primitive leaves or final POJOs that recursively prove clean). A non-final
     * user type, an interface, {@code Object}, or a raw/unresolved element type is treated as "may contain".
     */
    public static boolean mayContainHooks(final Class<?> type) {
        final Boolean cached = MAY_CONTAIN.get(type);
        if (cached != null) {
            return cached;
        }
        final boolean result = !isProvablyHookFree(type, new HashSet<Class<?>>(), null);
        MAY_CONTAIN.put(type, result);
        return result;
    }

    /**
     * As {@link #mayContainHooks(Class)}, judged with the mapper the value will be serialized by — which can
     * prove what a bare class cannot: a type that mapper writes as one scalar node has no fields in the tree,
     * so the walk stops there and nothing under it can carry a hook. That promotion needs the type to be
     * {@code final} (a subtype could be written as a bean after all) and to be hook-free ITSELF, so a
     * hook-bearing scalar type still gets its walk — and with it the warning that says why nothing fired.
     * A {@code null} mapper falls back to the mapper-free answer.
     */
    public static boolean mayContainHooks(final Class<?> type, final ObjectMapper mapper) {
        if (mapper == null) {
            return mayContainHooks(type);
        }
        final ConcurrentHashMap<Class<?>, Boolean> byType =
                MAY_CONTAIN_FOR_MAPPER.computeIfAbsent(mapper, m -> new ConcurrentHashMap<Class<?>, Boolean>());
        final Boolean cached = byType.get(type);
        if (cached != null) {
            return cached;
        }
        final boolean result = !isProvablyHookFree(type, new HashSet<Class<?>>(), mapper);
        byType.put(type, result);
        return result;
    }

    private static boolean isProvablyHookFree(final Class<?> type, final Set<Class<?>> onPath,
                                              final ObjectMapper mapper) {
        if (type == null || isHookFreeLeaf(type)) {
            return true;
        }
        if (LifecycleInvoker.hasHooks(type)) {
            return false;
        }
        if (!onPath.add(type)) {
            return true; // already being proven on this path: contributes no new hook by itself
        }
        try {
            for (final Field f : BindingNames.allFields(type)) {
                if (isSkippedField(f)) {
                    continue;
                }
                if (!valueTypeProvablyHookFree(f.getGenericType(), onPath, mapper)) {
                    return false;
                }
            }
            return true;
        } finally {
            onPath.remove(type);
        }
    }

    /**
     * Whether every value a slot of declared type {@code declared} can hold is provably hook-free. Containers
     * are unwrapped the way the walk descends them at runtime — an array by its component, a
     * {@code Collection} by its element type, a {@code Map} by its value type — and recursively, so a nested
     * {@code List<List<X>>} or {@code X[][]} is judged by what it ultimately holds rather than by the
     * container class in between. A type argument erasure leaves unresolved (raw, wildcard or type variable)
     * proves nothing.
     */
    private static boolean valueTypeProvablyHookFree(final Type declared, final Set<Class<?>> onPath,
                                                     final ObjectMapper mapper) {
        if (declared instanceof GenericArrayType) {
            return valueTypeProvablyHookFree(((GenericArrayType) declared).getGenericComponentType(), onPath, mapper);
        }
        final Class<?> raw = rawClassOf(declared);
        if (raw == null) {
            return false; // unresolved: whatever the runtime value is, the declaration does not pin it down
        }
        if (raw.isArray()) {
            return valueTypeProvablyHookFree(raw.getComponentType(), onPath, mapper);
        }
        if (Collection.class.isAssignableFrom(raw)) {
            return valueTypeProvablyHookFree(typeArgument(declared, 0), onPath, mapper);
        }
        if (Map.class.isAssignableFrom(raw)) {
            return valueTypeProvablyHookFree(typeArgument(declared, 1), onPath, mapper);
        }
        return elementProvablyHookFree(raw, onPath, mapper);
    }

    /**
     * Whether a value of static type {@code c} (a field type, or a resolved collection element/map value
     * type) can be proven hook-free. The walk descends into any user POJO at runtime, so nothing polymorphic
     * is provable — an interface, {@code Object} or a non-final class could be a hook-bearing subtype. What
     * remains provable is a leaf, a final POJO whose own fields recursively prove clean, and a final type
     * {@code mapper} writes as one scalar node, which the walk stops at and therefore cannot fire inside.
     * That last case is claimed only for a type carrying no hooks of its own: one that does is left to the
     * walk, which is where the warning explaining the silence comes from.
     */
    private static boolean elementProvablyHookFree(final Class<?> c, final Set<Class<?>> onPath,
                                                   final ObjectMapper mapper) {
        if (isHookFreeLeaf(c)) {
            return true;
        }
        if (c.isInterface() || c == Object.class || !Modifier.isFinal(c.getModifiers())) {
            return false; // a subtype could add hooks the static type does not reveal
        }
        if (!LifecycleInvoker.hasHooks(c) && !SerializedShape.emitsAsBean(mapper, c)) {
            return true; // written as one scalar node: the walk cannot reach a field of it
        }
        return isProvablyHookFree(c, onPath, mapper);
    }

    /** A type the walk neither fires nor descends into: a primitive/enum, or a JDK type (which cannot carry
     *  EveryConfig hooks and is treated as a leaf — the walk does not descend into JDK containers' contents
     *  beyond {@code Map}/{@code Collection}/array, which are handled structurally, not here). */
    private static boolean isHookFreeLeaf(final Class<?> c) {
        if (c.isPrimitive() || c.isEnum()) {
            return true;
        }
        final String n = c.getName();
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.");
    }

    /** The {@code index}-th type argument of {@code generic}, kept as a {@link Type} so a parameterized
     *  argument ({@code List<Box<K,V>>}) is handed on whole instead of being dropped for not being a class. */
    private static Type typeArgument(final Type generic, final int index) {
        if (generic instanceof ParameterizedType) {
            final Type[] args = ((ParameterizedType) generic).getActualTypeArguments();
            if (index < args.length) {
                return args[index];
            }
        }
        return null; // a raw container says nothing about what it holds
    }

    /** The class {@code type} erases to, or null for a wildcard/type variable — which names a bound, not the
     *  class the runtime value will actually have. */
    private static Class<?> rawClassOf(final Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            final Type raw = ((ParameterizedType) type).getRawType();
            return raw instanceof Class ? (Class<?>) raw : null;
        }
        return null;
    }
}
