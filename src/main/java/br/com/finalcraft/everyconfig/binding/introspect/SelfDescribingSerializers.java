package br.com.finalcraft.everyconfig.binding.introspect;

import br.com.finalcraft.everyconfig.selfdescribe.EveryConfigMap;
import br.com.finalcraft.everyconfig.selfdescribe.EveryConfigString;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.Serializers;

import java.io.IOException;

/**
 * Discovers a self-describing type ({@link EveryConfigString} / {@link EveryConfigMap}) at serialization
 * time and encodes it through the type's own form — no central registration. Registered once on every
 * codec's mapper; consulted for a solo value, a POJO field, and a collection element alike.
 */
final class SelfDescribingSerializers extends Serializers.Base {

    private static final JsonSerializer<Object> STRING_FORM = new StringForm();
    private static final JsonSerializer<Object> MAP_FORM = new MapForm();

    @Override
    public JsonSerializer<?> findSerializer(final SerializationConfig config, final JavaType type,
                                            final BeanDescription beanDesc) {
        final Class<?> raw = type.getRawClass();
        if (EveryConfigString.class.isAssignableFrom(raw)) {
            return STRING_FORM;
        }
        if (EveryConfigMap.class.isAssignableFrom(raw)) {
            return MAP_FORM;
        }
        return null;
    }

    private static final class StringForm extends JsonSerializer<Object> {
        @Override
        public void serialize(final Object value, final JsonGenerator gen, final SerializerProvider sp)
                throws IOException {
            gen.writeString(((EveryConfigString<?>) value).toConfigString());
        }
    }

    private static final class MapForm extends JsonSerializer<Object> {
        @Override
        public void serialize(final Object value, final JsonGenerator gen, final SerializerProvider sp)
                throws IOException {
            // Serialize the map through the provider so nested values honor the mapper (dates, other
            // self-describing types, ...), rather than writing raw Java objects.
            sp.defaultSerializeValue(((EveryConfigMap<?>) value).toConfigMap(), gen);
        }
    }
}
