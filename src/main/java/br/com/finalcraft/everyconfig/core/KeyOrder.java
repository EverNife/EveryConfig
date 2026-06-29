package br.com.finalcraft.everyconfig.core;

import br.com.finalcraft.everyconfig.core.tree.DPath;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable snapshot of each object's key order as it was read from the file, keyed by dotted path.
 * It is the authority for key ordering when the file is written back: keys appear in this captured order
 * first (for those still present), then any keys not in the snapshot follow in live-tree order.
 *
 * <p>This snapshot is needed because an {@code ObjectNode} (backed by a {@code LinkedHashMap}) moves a
 * key to the end when it is removed and re-added — so writing in live-tree order would silently reorder
 * a file on every save. Holding the original order separately keeps a removed-and-re-added key in its
 * original slot. The dynamic API never mutates it; it is captured on read and consulted on write.
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
    public static KeyOrder capture(final ObjectNode root) {
        final Map<String, List<String>> map = new LinkedHashMap<>();
        captureInto(root, "", map);
        return new KeyOrder(map);
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
        return orderByPath.isEmpty();
    }
}
