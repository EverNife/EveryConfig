package br.com.finalcraft.everyconfig.binding.merge;

import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.selfdescribe.CompactElementCodec;
import br.com.finalcraft.everyconfig.selfdescribe.CompactElementResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The collection-context codec for a type that has a distinct COMPACT element form (see
 * {@link CompactElementCodec}): it writes each element as a compact string and reads a list back tolerantly.
 * The sibling of {@link KeyIndexer} — both intercept the dynamic collection path to give a collection a layout
 * its element type would not get in the solo/field path — but the compact form is resolved per-codec via a
 * {@link CompactElementResolver} (annotations or a consumer's resolver), never a global marker.
 */
public final class ElementStringList {

    private ElementStringList() {
    }

    /**
     * Build a string-array node from {@code items}, each element via its resolved {@link CompactElementCodec}. A
     * {@code null} element becomes a JSON null; an element whose type resolves to no compact codec is rejected
     * (the caller classified the collection as compact from its first element, so a mismatch is a programming
     * error).
     */
    @SuppressWarnings("unchecked")
    public static ArrayNode toStringArray(final Collection<?> items, final JsonNodeFactory nf,
                                          final CompactElementResolver resolver) {
        final ArrayNode arr = nf.arrayNode();
        for (final Object item : items) {
            if (item == null) {
                arr.addNull();
                continue;
            }
            final CompactElementCodec<Object> codec =
                    (CompactElementCodec<Object>) resolver.resolve(item.getClass());
            if (codec == null) {
                throw new BindException("compact element write requires a compact element form for "
                        + item.getClass().getName());
            }
            arr.add(codec.encode(item));
        }
        return arr;
    }

    /**
     * Read a list tolerantly: a textual element is rebuilt via {@code codec.decode}, an object element via the
     * normal rich bind through {@code mapper}. A null or unreadable element is skipped (lenient, like the plain
     * list read).
     */
    public static <T> List<T> fromArray(final JsonNode node, final Class<T> type, final ObjectMapper mapper,
                                        final CompactElementCodec<T> codec) {
        final List<T> out = new ArrayList<>();
        if (!(node instanceof ArrayNode)) {
            return out;
        }
        for (final JsonNode element : node) {
            if (element == null || element.isNull()) {
                continue;
            }
            try {
                if (element.isTextual()) {
                    out.add(codec.decode(element.textValue()));
                } else {
                    out.add(mapper.convertValue(element, type)); // an object element: the normal rich bind
                }
            } catch (final RuntimeException badElement) {
                // lenient: skip an element that cannot be read in either the compact or the rich form
            }
        }
        return out;
    }
}
