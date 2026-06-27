package br.com.finalcraft.everyconfig.core.tree;

/**
 * Path grammar for the dynamic API. The default separator is {@code '.'}; a different one can be used
 * when a key may legitimately contain a dot.
 */
public final class PathOptions {

    public static final PathOptions DEFAULT = new PathOptions('.');

    private final char separator;

    public PathOptions(final char separator) {
        this.separator = separator;
    }

    public char separator() {
        return separator;
    }
}
