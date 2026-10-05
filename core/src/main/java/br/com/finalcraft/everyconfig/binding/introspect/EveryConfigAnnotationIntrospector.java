package br.com.finalcraft.everyconfig.binding.introspect;

import br.com.finalcraft.everyconfig.annotation.Key;
import br.com.finalcraft.everyconfig.binding.schema.BindingNames;
import br.com.finalcraft.everyconfig.core.coerce.TypeFamily;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds EveryConfig's key-naming annotations on top of Jackson's own. Name resolution is overridden, and
 * with it the property order of a type that renames a field; everything else ({@code @JsonIgnore}, {@code @JsonAlias}, {@code @JsonCreator}, visibility, ...) falls
 * through to the Jackson base, so native annotations keep working unchanged.
 *
 * <p>A {@link Key} renames a property and/or applies a case transform; with no {@code @Key} the Jackson
 * default name is kept.
 */
public final class EveryConfigAnnotationIntrospector extends JacksonAnnotationIntrospector {

    private static final long serialVersionUID = 1L;

    @Override
    public PropertyName findNameForSerialization(final Annotated a) {
        return resolve(a, super.findNameForSerialization(a));
    }

    @Override
    public PropertyName findNameForDeserialization(final Annotated a) {
        return resolve(a, super.findNameForDeserialization(a));
    }

    /**
     * Declaration order for a type with a renamed field. Jackson moves every renamed property behind the
     * ones that kept their name, so a {@code @Key} field declared first would be written after a plain field
     * declared last; an explicit order is the only thing that outranks that. A type's own
     * {@code @JsonPropertyOrder} still wins, and a type with no rename is left to Jackson.
     */
    @Override
    public String[] findSerializationPropertyOrder(final AnnotatedClass ac) {
        final String[] explicit = super.findSerializationPropertyOrder(ac);
        if (explicit != null || !TypeFamily.isUserPojoType(ac.getRawType())) {
            return explicit;
        }
        final List<String> names = new ArrayList<>();
        boolean renamed = false;
        for (Class<?> c = ac.getRawType(); c != null && c != Object.class; c = c.getSuperclass()) {
            final List<String> own = new ArrayList<>();
            for (final Field f : c.getDeclaredFields()) {
                own.add(f.getName()); // Jackson matches an order entry against the field name too
                renamed |= !BindingNames.keyFor(f).equals(f.getName());
            }
            names.addAll(0, own); // a superclass's fields come first, as Jackson itself lists them
        }
        return renamed ? names.toArray(new String[0]) : null;
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
