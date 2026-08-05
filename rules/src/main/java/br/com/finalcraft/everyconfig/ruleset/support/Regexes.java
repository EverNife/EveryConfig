package br.com.finalcraft.everyconfig.ruleset.support;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Compiles and caches the expressions the text constraints match against.
 *
 * <p>It takes the expression and the folded flag bits rather than the annotation, which keeps the regex
 * {@link Pattern} and jakarta's {@code @Pattern} from ever meeting in one file — and means the same
 * expression written on a thousand fields compiles once.
 */
public final class Regexes {

    private static final ConcurrentHashMap<String, Pattern> COMPILED = new ConcurrentHashMap<>();

    private Regexes() {
    }

    /** Whether {@code value} matches {@code regexp} end to end. */
    public static boolean matches(final String regexp, final int flags, final CharSequence value) {
        return compile(regexp, flags).matcher(value).matches();
    }

    /** The compiled expression, resolved once per (expression, flags) pair. */
    public static Pattern compile(final String regexp, final int flags) {
        final String key = flags + ":" + regexp;
        Pattern found = COMPILED.get(key);
        if (found == null) {
            found = Pattern.compile(regexp, flags);
            COMPILED.putIfAbsent(key, found);
        }
        return found;
    }
}
