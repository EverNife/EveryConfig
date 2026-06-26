package br.com.finalcraft.finalconfig.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable snapshot of the key order captured at load, per path (decision #4). It is the authority
 * for ordering at save (spec 01 §7.1): the emitter writes keys in this order first (for keys still
 * present), then appends keys absent from the snapshot in live-tree order — so a remove-then-readd keeps
 * the user's original file slot.
 *
 * <p>The dynamic API treats this as read-only; only load (phase 02) captures it and only the
 * emitter/migrations (phase 03/05) consult it. Phase 01 just holds it.
 */
public final class KeyOrder {

    private static final KeyOrder EMPTY = new KeyOrder(Collections.<String, List<String>>emptyMap());

    private final Map<String, List<String>> orderByPath;

    private KeyOrder(final Map<String, List<String>> orderByPath) {
        this.orderByPath = orderByPath;
    }

    public static KeyOrder empty() {
        return EMPTY;
    }

    /** Capture the key order of every object node in the tree, keyed by dotted path. */
    public static KeyOrder capture(final ObjectNode root, final char separator) {
        final Map<String, List<String>> map = new LinkedHashMap<>();
        captureInto(root, "", separator, map);
        return new KeyOrder(map);
    }

    private static void captureInto(final ObjectNode node, final String path, final char sep,
                                    final Map<String, List<String>> out) {
        final List<String> keys = new ArrayList<>();
        final Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            final Map.Entry<String, JsonNode> e = it.next();
            keys.add(e.getKey());
            if (e.getValue() instanceof ObjectNode) {
                final String childPath = path.isEmpty() ? e.getKey() : path + sep + e.getKey();
                captureInto((ObjectNode) e.getValue(), childPath, sep, out);
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
        return orderByPath.isEmpty();
    }
}
