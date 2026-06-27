package br.com.finalcraft.everyconfig.binding.introspect;

import br.com.finalcraft.everyconfig.annotation.Key;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;

/**
 * Adds FinalConfig's key-naming annotations on top of Jackson's own. Only name resolution is overridden;
 * everything else ({@code @JsonIgnore}, {@code @JsonAlias}, {@code @JsonCreator}, visibility, ...) falls
 * through to the Jackson base, so native annotations keep working unchanged.
 *
 * <p>A {@link Key} renames a property and/or applies a case transform; with no {@code @Key} the Jackson
 * default name is kept.
 */
public final class FinalConfigAnnotationIntrospector extends JacksonAnnotationIntrospector {

    private static final long serialVersionUID = 1L;

    @Override
    public PropertyName findNameForSerialization(final Annotated a) {
        return resolve(a, super.findNameForSerialization(a));
    }

    @Override
    public PropertyName findNameForDeserialization(final Annotated a) {
        return resolve(a, super.findNameForDeserialization(a));
    }

    private PropertyName resolve(final Annotated a, final PropertyName fallback) {
        final Key key = a.getAnnotation(Key.class);
        if (key == null) {
            return fallback;
        }
        final String base = key.value().isEmpty() ? defaultName(a, fallback) : key.value();
        return PropertyName.construct(key.transformCase().apply(base));
    }

    /** The implicit bean name Jackson already resolved (e.g. {@code maxSize} for {@code getMaxSize}). */
    private static String defaultName(final Annotated a, final PropertyName fallback) {
        if (fallback != null && fallback.hasSimpleName()) {
            return fallback.getSimpleName();
        }
        return a.getName();
    }
}
