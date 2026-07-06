package br.com.finalcraft.everyconfig.binding.introspect;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.Serializers;

/**
 * Resolves the serializer for every enum to {@link EnumNameSerializer} (by {@code name()} — stable and
 * human-editable in a config), EXCEPT an enum that declares a {@code @JsonValue} accessor, which is
 * self-describing and keeps its custom form. Returning null for a {@code @JsonValue} enum lets Jackson's
 * own annotation handling take over.
 *
 * <p>This is a resolver rather than a blanket {@code addSerializer(Enum.class, ...)} type mapping precisely
 * so the {@code @JsonValue} case can opt out: a blanket mapping is consulted before Jackson's annotation
 * handling and would override {@code @JsonValue}.
 */
final class EnumNameSerializers extends Serializers.Base {

    private final JsonSerializer<?> enumByName = new EnumNameSerializer();

    @Override
    public JsonSerializer<?> findSerializer(final SerializationConfig config, final JavaType type,
                                            final BeanDescription beanDesc) {
        if (!Enum.class.isAssignableFrom(type.getRawClass())) {
            return null;
        }
        if (beanDesc.findJsonValueAccessor() != null) {
            return null; // a @JsonValue enum is self-describing; let Jackson honor its custom form
        }
        return enumByName;
    }
}
