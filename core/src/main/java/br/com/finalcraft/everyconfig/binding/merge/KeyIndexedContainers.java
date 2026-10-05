package br.com.finalcraft.everyconfig.binding.merge;

import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.binding.schema.BindingNames;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.type.ArrayType;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.util.TokenBuffer;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Teaches a mapper the key-major layout for a collection or array whose DECLARED element type carries
 * {@code @KeyIndex}: wherever the mapper writes one — a bean field, at any depth — it becomes a section keyed
 * by each element's id, the id left out of the body, and it reads back from that section. A plain array still
 * reads, and an empty container is written as an empty array, there being no element to key it by.
 */
public final class KeyIndexedContainers {

    private KeyIndexedContainers() {
    }

    public static void register(final Module.SetupContext context) {
        context.addBeanSerializerModifier(new SerializerModifier());
        context.addBeanDeserializerModifier(new DeserializerModifier());
    }

    /**
     * {@code mapper.valueToTree(value)}, except that a {@link BindException} raised while writing a key-major
     * section (a blank or duplicate id) reaches the caller as itself instead of inside the mapper's wrapper.
     */
    public static JsonNode toTree(final ObjectMapper mapper, final Object value) {
        try {
            return mapper.valueToTree(value);
        } catch (final IllegalArgumentException wrapped) {
            for (Throwable cause = wrapped.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof BindException) {
                    throw (BindException) cause;
                }
            }
            throw wrapped;
        }
    }

    private static final class SerializerModifier extends BeanSerializerModifier {

        private static final long serialVersionUID = 1L;

        @Override
        public JsonSerializer<?> modifyCollectionSerializer(final SerializationConfig config,
                                                            final CollectionType valueType,
                                                            final BeanDescription beanDesc,
                                                            final JsonSerializer<?> serializer) {
            return BindingNames.isKeyIndexedContainer(valueType) ? new Serializer() : serializer;
        }

        @Override
        public JsonSerializer<?> modifyArraySerializer(final SerializationConfig config, final ArrayType valueType,
                                                       final BeanDescription beanDesc,
                                                       final JsonSerializer<?> serializer) {
            return BindingNames.isKeyIndexedContainer(valueType) ? new Serializer() : serializer;
        }
    }

    private static final class DeserializerModifier extends BeanDeserializerModifier {

        private static final long serialVersionUID = 1L;

        @Override
        public JsonDeserializer<?> modifyCollectionDeserializer(final DeserializationConfig config,
                                                                final CollectionType type,
                                                                final BeanDescription beanDesc,
                                                                final JsonDeserializer<?> deserializer) {
            return BindingNames.isKeyIndexedContainer(type) ? new Deserializer(type, deserializer) : deserializer;
        }

        @Override
        public JsonDeserializer<?> modifyArrayDeserializer(final DeserializationConfig config, final ArrayType type,
                                                           final BeanDescription beanDesc,
                                                           final JsonDeserializer<?> deserializer) {
            return BindingNames.isKeyIndexedContainer(type) ? new Deserializer(type, deserializer) : deserializer;
        }
    }

    private static final class Serializer extends StdSerializer<Object> {

        private static final long serialVersionUID = 1L;

        Serializer() {
            super(Object.class);
        }

        @Override
        public boolean isEmpty(final SerializerProvider provider, final Object value) {
            return elementsOf(value).isEmpty();
        }

        @Override
        public void serialize(final Object value, final JsonGenerator gen, final SerializerProvider provider)
                throws IOException {
            final Collection<?> elements = elementsOf(value);
            if (elements.isEmpty()) {
                gen.writeStartArray();
                gen.writeEndArray();
                return;
            }
            gen.writeStartObject();
            final Set<String> written = new HashSet<>();
            for (final Object entity : elements) {
                if (entity == null) {
                    throw new BindException("A null element in a collection of @KeyIndex entities has no id to "
                            + "be stored under. Remove the null from the collection before saving it.");
                }
                final Field id = BindingNames.requireSingleKeyIndex(entity.getClass());
                final String key = KeyIndexer.sectionKey(entity, id);
                if (!written.add(key)) {
                    throw KeyIndexer.duplicateKey(key, entity);
                }
                gen.writeFieldName(key);
                writeBodyWithoutId(entity, KeyIndexer.resolvedIdKey(entity.getClass(), id, provider.getConfig()),
                        gen, provider);
            }
            gen.writeEndObject();
        }

        private static Collection<?> elementsOf(final Object container) {
            return container instanceof Collection ? (Collection<?>) container : Arrays.asList((Object[]) container);
        }

        /** The entity as the mapper writes it, minus the id property — the section key already carries it. */
        private static void writeBodyWithoutId(final Object entity, final String idKey, final JsonGenerator gen,
                                               final SerializerProvider provider) throws IOException {
            final TokenBuffer body = new TokenBuffer(gen.getCodec(), false);
            provider.defaultSerializeValue(entity, body);
            final JsonParser p = body.asParser();
            if (p.nextToken() != JsonToken.START_OBJECT) {
                gen.copyCurrentStructure(p); // not written as fields, so there is no id property to leave out
                return;
            }
            gen.writeStartObject();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                final String name = p.currentName();
                p.nextToken();
                if (name.equals(idKey)) {
                    p.skipChildren();
                } else {
                    gen.writeFieldName(name);
                    gen.copyCurrentStructure(p);
                }
            }
            gen.writeEndObject();
        }
    }

    /** Reads the key-major section; anything that is not an object goes to the plain deserializer it wraps. */
    private static final class Deserializer extends DelegatingDeserializer {

        private static final long serialVersionUID = 1L;

        private final JavaType containerType;

        Deserializer(final JavaType containerType, final JsonDeserializer<?> plain) {
            super(plain);
            this.containerType = containerType;
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(final JsonDeserializer<?> newDelegatee) {
            return new Deserializer(containerType, newDelegatee);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object deserialize(final JsonParser p, final DeserializationContext ctxt) throws IOException {
            if (!p.isExpectedStartObjectToken()) {
                return _delegatee.deserialize(p, ctxt);
            }
            final List<Object> elements = readSection(p, ctxt);
            if (containerType.isArrayType()) {
                return elements.toArray((Object[]) Array.newInstance(
                        containerType.getContentType().getRawClass(), elements.size()));
            }
            // An empty array read by the plain deserializer is an instance of the declared container kind.
            final JsonParser empty = ctxt.getNodeFactory().arrayNode().traverse(p.getCodec());
            empty.nextToken();
            final Collection<Object> out = (Collection<Object>) _delegatee.deserialize(empty, ctxt);
            out.addAll(elements);
            return out;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object deserialize(final JsonParser p, final DeserializationContext ctxt, final Object intoValue)
                throws IOException {
            if (p.isExpectedStartObjectToken() && intoValue instanceof Collection) {
                ((Collection<Object>) intoValue).addAll(readSection(p, ctxt));
                return intoValue;
            }
            return super.deserialize(p, ctxt, intoValue);
        }

        /**
         * One element per section key. A failure is thrown with the key on its path, so a lenient bind names
         * and isolates the single entry (or the single field inside it) instead of losing the collection.
         */
        private List<Object> readSection(final JsonParser p, final DeserializationContext ctxt) throws IOException {
            final JavaType elementType = containerType.getContentType();
            final JsonDeserializer<Object> elementDeserializer = ctxt.findRootValueDeserializer(elementType);
            final List<Object> out = new ArrayList<>();
            for (String key = p.nextFieldName(); key != null; key = p.nextFieldName()) {
                try {
                    if (p.nextToken() == JsonToken.VALUE_NULL) {
                        throw MismatchedInputException.from(p, elementType, "The section '" + key + "' has no "
                                + "body, so there is no " + elementType.getRawClass().getSimpleName()
                                + " to read from it. Give it at least one field, or remove the key.");
                    }
                    final Object entity = elementDeserializer.deserialize(p, ctxt);
                    KeyIndexer.assignId(entity, key);
                    out.add(entity);
                } catch (final Exception e) {
                    throw JsonMappingException.wrapWithPath(e, out, key);
                }
            }
            return out;
        }
    }
}
