package br.com.finalcraft.everyconfig.core;

import br.com.finalcraft.everyconfig.core.tree.DPath;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An immutable snapshot of each object's key order as it was read from the file, keyed by dotted path, plus
 * an optional overlay of per-key <em>pin</em> directives. It is the authority for key ordering when the file
 * is written back (via {@link #arrange}): captured keys appear in their original order first (for those still
 * present), then any keys not in the snapshot follow in live-tree order — and finally that base order is
 * re-partitioned by pin {@link Zone} so a {@link Zone#FIRST} key floats above its siblings and a
 * {@link Zone#LAST} key sinks below them.
 *
 * <p>The snapshot is needed because an {@code ObjectNode} (backed by a {@code LinkedHashMap}) moves a key to
 * the end when it is removed and re-added — so writing in live-tree order would silently reorder a file on
 * every save. Holding the original order separately keeps a removed-and-re-added key in its original slot.
 *
 * <p>Pins are an in-memory ordering POLICY, never read from the file: they let a caller keep, say, a
 * {@code Debug} section at the bottom even as new keys are seeded over time. The dynamic API never mutates a
 * {@code KeyOrder}; it is captured on read and consulted on write, and pin updates return a new instance.
 */
public final class KeyOrder {

    /** Where a key sits relative to its siblings when emitted: floated to the top, sunk to the bottom, or
     *  left in the captured/append order. */
    public enum Zone {
        FIRST, NORMAL, LAST
    }

    private static final KeyOrder EMPTY = new KeyOrder(
            Collections.<String, List<String>>emptyMap(),
            Collections.<String, Map<String, Zone>>emptyMap());

    private final Map<String, List<String>> orderByPath;
    // Pin directives: parentPath -> (literal key -> zone). A key absent here is NORMAL. Immutable, and
    // separate from the captured order so a reload (which recaptures orderByPath) can re-apply the same pins.
    private final Map<String, Map<String, Zone>> pinByPath;

    private KeyOrder(final Map<String, List<String>> orderByPath,
                     final Map<String, Map<String, Zone>> pinByPath) {
        this.orderByPath = orderByPath;
        this.pinByPath = pinByPath;
    }

    public static KeyOrder empty() {
        return EMPTY;
    }

    /** Capture the key order of every object node in the tree, keyed by dotted path. Carries no pins. */
    public static KeyOrder capture(final ObjectNode root) {
        final Map<String, List<String>> map = new LinkedHashMap<>();
        captureInto(root, "", map);
        return new KeyOrder(map, Collections.<String, Map<String, Zone>>emptyMap());
    }

    private static void captureInto(final ObjectNode node, final String path,
                                    final Map<String, List<String>> out) {
        final List<String> keys = new ArrayList<>();
        final Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            final Map.Entry<String, JsonNode> e = it.next();
            keys.add(e.getKey());
            if (e.getValue() instanceof ObjectNode) {
                final String childPath = DPath.joinSegment(path, e.getKey());
                captureInto((ObjectNode) e.getValue(), childPath, out);
            }
        }
        out.put(path, keys);
    }

    /** Ordered keys captured for {@code path} at load, or an empty list if none. */
    public List<String> orderedKeys(final String path) {
        final List<String> keys = orderByPath.get(path == null ? "" : path);
        return keys == null ? Collections.<String>emptyList() : Collections.unmodifiableList(keys);
    }

    public boolean isEmpty() {
        return orderByPath.isEmpty() && pinByPath.isEmpty();
    }

    /**
     * A copy of this order with the literal leaf {@code key} under {@code parentPath} assigned to
     * {@code zone} — the immutable pin update. {@link Zone#NORMAL} (or {@code null}) clears an existing pin.
     * The captured order is shared (it is immutable); only the pin overlay is copied.
     */
    public KeyOrder withPin(final String parentPath, final String key, final Zone zone) {
        final String pp = parentPath == null ? "" : parentPath;
        final Map<String, Map<String, Zone>> copy = new LinkedHashMap<>(pinByPath);
        final Map<String, Zone> inner = new LinkedHashMap<>(
                copy.containsKey(pp) ? copy.get(pp) : Collections.<String, Zone>emptyMap());
        if (zone == null || zone == Zone.NORMAL) {
            inner.remove(key);
        } else {
            inner.put(key, zone);
        }
        if (inner.isEmpty()) {
            copy.remove(pp);
        } else {
            copy.put(pp, inner);
        }
        return new KeyOrder(orderByPath, copy);
    }

    /** A copy of this order with any pin on {@code key} under {@code parentPath} removed. */
    public KeyOrder withoutPin(final String parentPath, final String key) {
        return withPin(parentPath, key, Zone.NORMAL);
    }

    /**
     * The final emit order for an object at {@code parentPath} given its {@code live} keys (an insertion-order
     * set the caller already holds, used as-is for O(1) membership): the captured order first (for keys still
     * present), then any live keys not in the snapshot — then re-partitioned by pin zone so {@link Zone#FIRST}
     * keys lead and {@link Zone#LAST} keys trail, each preserving that base order. With no pins at this path
     * it is exactly the historical "captured, then appended" behavior.
     */
    public List<String> arrange(final String parentPath, final Set<String> live) {
        final String pp = parentPath == null ? "" : parentPath;
        // Base: captured order (filtered by live), then any remaining live key. A LinkedHashSet keeps
        // membership O(1): an ArrayList.contains here was O(n^2) and dominated the save of a wide node.
        final LinkedHashSet<String> base = new LinkedHashSet<>(Math.max(16, live.size() * 2));
        for (final String k : orderedKeys(pp)) {
            if (live.contains(k)) {
                base.add(k);
            }
        }
        base.addAll(live);

        final Map<String, Zone> pins = pinByPath.get(pp);
        if (pins == null || pins.isEmpty()) {
            return new ArrayList<>(base);
        }
        final List<String> first = new ArrayList<>();
        final List<String> normal = new ArrayList<>();
        final List<String> last = new ArrayList<>();
        for (final String k : base) {
            final Zone z = pins.get(k);
            if (z == Zone.FIRST) {
                first.add(k);
            } else if (z == Zone.LAST) {
                last.add(k);
            } else {
                normal.add(k);
            }
        }
        final List<String> out = new ArrayList<>(base.size());
        out.addAll(first);
        out.addAll(normal);
        out.addAll(last);
        return out;
    }
}
