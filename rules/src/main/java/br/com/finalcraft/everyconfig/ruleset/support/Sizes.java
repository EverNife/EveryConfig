package br.com.finalcraft.everyconfig.ruleset.support;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;

/** The one definition of "how big is this value" the size constraints share. */
public final class Sizes {

    private Sizes() {
    }

    /** How many characters, entries or elements {@code value} holds; -1 when it has no size at all. */
    public static int lengthOf(final Object value) {
        if (value instanceof CharSequence) {
            return ((CharSequence) value).length();
        }
        if (value instanceof Collection) {
            return ((Collection<?>) value).size();
        }
        if (value instanceof Map) {
            return ((Map<?, ?>) value).size();
        }
        if (value != null && value.getClass().isArray()) {
            return Array.getLength(value);
        }
        return -1;
    }

    /** Whether {@code text} holds no non-whitespace character — the blank of {@code @NotBlank}. */
    public static boolean isBlank(final CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
