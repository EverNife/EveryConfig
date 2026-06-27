package br.com.finalcraft.finalconfig.binding.merge;
import br.com.finalcraft.finalconfig.binding.BindOptions;
import br.com.finalcraft.finalconfig.binding.schema.Schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Merges a POJO-derived tree INTO the canonical tree, never replacing it. The POJO is the source of
 * truth for the keys it declares; everything else the canonical tree already holds survives. Nested
 * objects merge recursively; arrays and scalars are taken whole from the POJO (a list has no stable
 * identity to merge element-by-element). A key the canonical tree has but the POJO does not is dropped
 * only under an explicit REMOVE policy and only at a level the schema fully owns — never inside a
 * free-form map.
 */
public final class SmartMerge {

    private SmartMerge() {
    }

    public static void mergeInto(final ObjectNode canonical, final ObjectNode candidate,
                          final Schema schema, final BindOptions options) {
        final Iterator<String> names = candidate.fieldNames();
        while (names.hasNext()) {
            final String key = names.next();
            final JsonNode cand = candidate.get(key);
            final JsonNode canon = canonical.get(key);
            if (canon == null) {
                canonical.set(key, cand); // missing from the file -> seed from the POJO, appended
            } else if (canon.isObject() && cand.isObject()) {
                mergeInto((ObjectNode) canon, (ObjectNode) cand, schema.child(key), options);
            } else {
                canonical.set(key, cand); // arrays, scalars, and mixed kinds -> the POJO value wins
            }
        }

        if (options.obsoletePolicy() == BindOptions.ObsoletePolicy.REMOVE && schema.isClosed()) {
            final List<String> obsolete = new ArrayList<>();
            final Iterator<String> canonNames = canonical.fieldNames();
            while (canonNames.hasNext()) {
                final String key = canonNames.next();
                if (!candidate.has(key) && schema.isObsolete(key)) {
                    obsolete.add(key);
                }
            }
            for (final String key : obsolete) {
                canonical.remove(key);
            }
        }
    }
}
