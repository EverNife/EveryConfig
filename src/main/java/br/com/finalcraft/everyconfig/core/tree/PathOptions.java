package br.com.finalcraft.everyconfig.core.tree;

/**
 * Path grammar for the dynamic API. The separator is fixed at {@code '.'}; a key that legitimately
 * contains a dot is expressed by escaping it ({@code a\.b} addresses the single key {@code "a.b"}), so
 * there is no swappable separator to choose. Only {@link #DEFAULT} exists.
 */
public final class PathOptions {

    public static final PathOptions DEFAULT = new PathOptions('.');

    private final char separator;

    private PathOptions(final char separator) {
        this.separator = separator;
    }

    public char separator() {
        return separator;
    }
}
