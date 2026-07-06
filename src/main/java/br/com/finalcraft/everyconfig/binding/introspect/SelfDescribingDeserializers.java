package br.com.finalcraft.everyconfig.binding.introspect;

import br.com.finalcraft.everyconfig.selfdescribe.EveryConfigMap;
import br.com.finalcraft.everyconfig.selfdescribe.EveryConfigString;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.Deserializers;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The read half of the marker-interface mechanism: for a {@link EveryConfigString} / {@link EveryConfigMap}
 * type, binds through the type's static factory (found by convention via {@link ConventionFactory}), so a
 * self-describing type needs no central registration on either the write or the read side.
 */
final class SelfDescribingDeserializers extends Deserializers.Base {

    @Override
    public JsonDeserializer<?> findBeanDeserializer(final JavaType type, final DeserializationConfig config,
                                                    final BeanDescription beanDesc) {
        final Class<?> raw = type.getRawClass();
        if (EveryConfigString.class.isAssignableFrom(raw)) {
            return new StringForm(raw);
        }
        if (EveryConfigMap.class.isAssignableFrom(raw)) {
            return new MapForm(raw);
        }
        return null;
    }

    private static final class StringForm extends JsonDeserializer<Object> {
        private final Class<?> raw;

        StringForm(final Class<?> raw) {
            this.raw = raw;
        }

        @Override
        public Object deserialize(final JsonParser p, final DeserializationContext ctxt) throws IOException {
            final String s = p.getValueAsString();
            if (s == null) {
                return null;
            }
            return ConventionFactory.invoke(
                    ConventionFactory.require(raw, "fromConfigString", String.class), s);
        }
    }

    private static final class MapForm extends JsonDeserializer<Object> {
        private final Class<?> raw;

        MapForm(final Class<?> raw) {
            this.raw = raw;
        }

        @Override
        public Object deserialize(final JsonParser p, final DeserializationContext ctxt) throws IOException {
            final JavaType mapType = ctxt.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class);
            final Map<String, Object> map = ctxt.readValue(p, mapType);
            return ConventionFactory.invoke(
                    ConventionFactory.require(raw, "fromConfigMap", Map.class), map);
        }
    }
}
