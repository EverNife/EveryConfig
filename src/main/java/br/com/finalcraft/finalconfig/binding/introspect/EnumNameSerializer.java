package br.com.finalcraft.finalconfig.binding.introspect;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * Serializes any enum by its {@code name()}. Without this, an enum that carries instance fields or
 * overrides {@code toString()} can serialize as an object or a custom string, which then fails to read
 * back by name. Forcing {@code name()} keeps enums stable and human-editable in the config.
 */
final class EnumNameSerializer extends JsonSerializer<Enum<?>> {

    @Override
    public void serialize(final Enum<?> value, final JsonGenerator gen, final SerializerProvider serializers)
            throws IOException {
        gen.writeString(value.name());
    }
}
