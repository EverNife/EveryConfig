package br.com.finalcraft.everyconfig.annotation;

/**
 * How a Java field name is transformed into the on-disk key. {@link #apply(String)} performs the
 * transform, so the same logic backs both the per-field {@link Key} transform and a class-wide naming
 * strategy.
 */
public enum KeyTransformCase {

    /** Use the name unchanged. */
    NONE,
    /** {@code maxPoolSize} -> {@code max-pool-size}. */
    KEBAB_CASE,
    /** {@code maxPoolSize} -> {@code max_pool_size}. */
    SNAKE_CASE,
    /** Force a lowercase first letter ({@code MaxPoolSize} -> {@code maxPoolSize}). */
    CAMEL_CASE,
    /** Force an uppercase first letter ({@code maxPoolSize} -> {@code MaxPoolSize}). */
    UPPER_CAMEL_CASE;

    public String apply(final String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        switch (this) {
            case KEBAB_CASE:
                return delimit(name, '-');
            case SNAKE_CASE:
                return delimit(name, '_');
            case CAMEL_CASE:
                return Character.toLowerCase(name.charAt(0)) + name.substring(1);
            case UPPER_CAMEL_CASE:
                return Character.toUpperCase(name.charAt(0)) + name.substring(1);
            case NONE:
            default:
                return name;
        }
    }

    /** Insert {@code delimiter} before each interior uppercase letter, then lowercase everything. */
    private static String delimit(final String name, final char delimiter) {
        final StringBuilder sb = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != delimiter) {
                    sb.append(delimiter);
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
