package br.com.finalcraft.everyconfig.binding.merge;

import br.com.finalcraft.everyconfig.annotation.Section;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.binding.merge.LifecycleInvoker.Phase;
import br.com.finalcraft.everyconfig.binding.schema.BindingNames;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.section.ConfigSection;
import br.com.finalcraft.everyconfig.core.coerce.TypeFamily;
import br.com.finalcraft.everyconfig.core.tree.DPath;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
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

    /** Types already warned about being serialized as a compact element while carrying hooks — the warning
     *  is emitted once per type, not once per element/save. */
    private static final Set<Class<?>> WARNED_COMPACT =
            Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());

    private final Config config;
    private final Phase phase;
    private final List<LoadIssue> issues;
    private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());

    private LifecycleGraphWalker(final Config config, final Phase phase, final List<LoadIssue> issues) {
        this.config = config;
        this.phase = phase;
        this.issues = issues;
    }

    // ==================== entity read/write: descendants of an already-fired root ====================

    /**
     * Fire {@code phase} for every hook-bearing DESCENDANT of {@code root} (fields, {@code Map} values,
     * collection/array elements, recursively), each with a section at its sub-path under {@code rootPath}.
     * {@code root} itself is NOT fired — its caller ({@code EntityBinder}) already did.
     */
    public static void fireDescendants(final Config config, final Object root, final String rootPath,
                                       final Phase phase, final List<LoadIssue> issues) {
        if (root == null) {
            return;
        }
        final LifecycleGraphWalker w = new LifecycleGraphWalker(config, phase, issues);
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
        final LifecycleGraphWalker w = new LifecycleGraphWalker(config, phase, issues);
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
        final LifecycleGraphWalker w = new LifecycleGraphWalker(config, phase, issues);
        for (final Map.Entry<?, ?> e : map.entrySet()) {
            w.visit(e.getValue(), DPath.joinSegment(basePath, String.valueOf(e.getKey())));
        }
    }

    /** Whether any element carries (or may transitively reach) hooks — the cheap gate the {@code Config} seam
     *  uses so a scalar collection/map skips the firing machinery entirely. */
    public static boolean anyMayHaveHooks(final Iterable<?> values) {
        for (final Object v : values) {
            if (v != null && mayContainHooks(v.getClass())) {
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

    // ==================== the walk ====================

    /** Fire {@code value} if it is hook-bearing, then descend into it. A node already visited (cycle or
     *  shared reference) is skipped, so each instance fires at most once per walk. */
    private void visit(final Object value, final String path) {
        if (value == null || !visited.add(value)) {
            return;
        }
        final Class<?> c = value.getClass();
        if (LifecycleInvoker.hasHooks(c)) {
            LifecycleInvoker.fire(value, phase, new ConfigSection(config, path), issues);
        }
        descend(value, c, path);
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
        } else if (TypeFamily.isUserPojoType(c)) {
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
        final boolean result = !isProvablyHookFree(type, new HashSet<Class<?>>());
        MAY_CONTAIN.put(type, result);
        return result;
    }

    private static boolean isProvablyHookFree(final Class<?> type, final Set<Class<?>> onPath) {
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
                if (!fieldProvablyHookFree(f, onPath)) {
                    return false;
                }
            }
            return true;
        } finally {
            onPath.remove(type);
        }
    }

    private static boolean fieldProvablyHookFree(final Field f, final Set<Class<?>> onPath) {
        final Class<?> ft = f.getType();
        if (ft.isArray()) {
            return elementProvablyHookFree(ft.getComponentType(), onPath);
        }
        if (Collection.class.isAssignableFrom(ft)) {
            return elementProvablyHookFree(typeArgument(f.getGenericType(), 0), onPath);
        }
        if (Map.class.isAssignableFrom(ft)) {
            return elementProvablyHookFree(typeArgument(f.getGenericType(), 1), onPath);
        }
        return elementProvablyHookFree(ft, onPath);
    }

    /** Whether a value of static type {@code c} (a field type, or a resolved collection element/map value
     *  type) can be proven hook-free. The walker descends into any user POJO at runtime, so only leaves and
     *  final POJOs are provable; anything polymorphic (interface/{@code Object}/non-final/unresolved) is not. */
    private static boolean elementProvablyHookFree(final Class<?> c, final Set<Class<?>> onPath) {
        if (c == null) {
            return false; // a raw or wildcard element type could hold anything at runtime
        }
        if (isHookFreeLeaf(c)) {
            return true;
        }
        if (c.isInterface() || c == Object.class || !Modifier.isFinal(c.getModifiers())) {
            return false; // a subtype could add hooks the static type does not reveal
        }
        return isProvablyHookFree(c, onPath);
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

    private static Class<?> typeArgument(final Type generic, final int index) {
        if (generic instanceof ParameterizedType) {
            final Type[] args = ((ParameterizedType) generic).getActualTypeArguments();
            if (index < args.length && args[index] instanceof Class) {
                return (Class<?>) args[index];
            }
        }
        return null; // raw, wildcard, or type-variable element: not statically resolvable
    }
}
