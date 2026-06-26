package br.com.finalcraft.finalconfig.core.tree;

/**
 * Path grammar for the dynamic API. Default separator is {@code '.'}, matching the old project's
 * {@code pathSeparator()} (which old callers could change).
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
