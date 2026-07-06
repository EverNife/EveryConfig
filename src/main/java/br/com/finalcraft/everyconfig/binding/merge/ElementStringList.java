package br.com.finalcraft.everyconfig.binding.merge;

import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.binding.introspect.ConventionFactory;
import br.com.finalcraft.everyconfig.selfdescribe.EveryConfigElementString;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The collection-context codec for a type with a distinct compact element form
 * ({@link EveryConfigElementString}): it writes each element as a compact string and reads a list back
 * tolerantly. The sibling of {@link KeyIndexer} — both intercept the dynamic collection path to give a
 * collection a layout its element type would not get in the solo/field path — but this one is opt-in (only
 * the {@code setElementList}/{@code getElementList} calls route here, never a plain {@code setValue}).
 */
public final class ElementStringList {

    private ElementStringList() {
    }

    /**
     * Build a string-array node from {@code items}, each element via {@link EveryConfigElementString}. A
     * {@code null} element becomes a JSON null; an element not declaring the compact form is rejected (the
     * caller opted into a compact write, so a non-compact element is a programming error).
     */
    public static ArrayNode toStringArray(final Collection<?> items, final JsonNodeFactory nf) {
        final ArrayNode arr = nf.arrayNode();
        for (final Object item : items) {
            if (item == null) {
                arr.addNull();
            } else if (item instanceof EveryConfigElementString) {
                arr.add(((EveryConfigElementString<?>) item).toElementString());
            } else {
                throw new BindException("setElementList requires elements implementing EveryConfigElementString; got "
                        + item.getClass().getName());
            }
        }
        return arr;
    }

    /**
     * Read a list tolerantly: a textual element is rebuilt via the type's {@code fromElementString} factory,
     * an object element via the normal rich bind through {@code mapper}. A null or unreadable element is
     * skipped (lenient, like the plain list read).
     */
    public static <T> List<T> fromArray(final JsonNode node, final Class<T> type, final ObjectMapper mapper) {
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
                    out.add(type.cast(ConventionFactory.invoke(
                            ConventionFactory.require(type, "fromElementString", String.class),
                            element.textValue())));
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
