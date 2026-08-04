package br.com.finalcraft.everyconfig.binding;

/**
 * One value that could not be bound to its target type during a lenient load. Recording the issue lets a
 * single bad value degrade gracefully (the field keeps a default) instead of failing the whole load; a
 * {@code @PostLoad} validator can inspect the collected issues and decide whether to reject the config.
 */
public final class LoadIssue {

    private final String key;
    private final Object rawValue;
    private final Class<?> targetType;
    private final String message;

    public LoadIssue(final String key, final Object rawValue, final Class<?> targetType, final String message) {
        this.key = key;
        this.rawValue = rawValue;
        this.targetType = targetType;
        this.message = message;
    }

    public String key() {
        return key;
    }

    public Object rawValue() {
        return rawValue;
    }

    public Class<?> targetType() {
        return targetType;
    }

    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return "'" + key + "' = '" + rawValue + "' (expected "
                + (targetType == null ? "?" : targetType.getSimpleName()) + "): " + message;
    }
}
