package br.com.finalcraft.everyconfig.binding;

import br.com.finalcraft.everyconfig.annotation.PostLoad;
import br.com.finalcraft.everyconfig.annotation.PostSave;
import br.com.finalcraft.everyconfig.annotation.PreSave;
import br.com.finalcraft.everyconfig.binding.merge.LifecycleGraphWalker;
import br.com.finalcraft.everyconfig.binding.merge.SerializedShape;
import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.jackson.YamlCodec;
import br.com.finalcraft.everyconfig.config.Config;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A type whose serialized form a Jackson module owns — one scalar token, not an object of fields — crossing
 * the binder as an opaque leaf. Because such a value has no sub-tree, it has no sub-paths either: the
 * lifecycle walk must neither descend into it nor fire anything declared inside it, and the static gate may
 * treat it as proven clean. Registering the module is the whole contract; EveryConfig is told nothing else.
 *
 * <p>Codec-independent (the mapper decides the shape, not the file format), so it runs once here rather than
 * across the codec matrix.
 */
class OpaqueScalarBindingTest {

    /** Callbacks that fired where none should: an unwanted descent is reported as data at the assertion,
     *  rather than as an exception thrown from the middle of a save. */
    private static final List<String> UNEXPECTED = Collections.synchronizedList(new ArrayList<String>());

    @BeforeEach
    void resetRecorder() {
        UNEXPECTED.clear();
    }

    // ==================== the type a Jackson module owns ====================

    /**
     * The store a {@link Handle} resolves its key against. Hook-bearing and reachable ONLY through a Handle's
     * own field, so any walk that steps inside a Handle announces itself here — the whole point of it living
     * in a non-transient field.
     */
    static final class Registry implements ConfigLifecycle {
        private final Map<String, Object> payloads = new LinkedHashMap<>();

        Registry put(final String key, final Object payload) {
            payloads.put(key, payload);
            return this;
        }

        @Override
        public void postLoad(final ConfigContext context) {
            UNEXPECTED.add("Registry.postLoad@" + context.section().getPath());
        }

        @Override
        public void preSave(final ConfigContext context) {
            UNEXPECTED.add("Registry.preSave@" + context.section().getPath());
        }

        @Override
        public void postSave(final ConfigContext context) {
            UNEXPECTED.add("Registry.postSave@" + context.section().getPath());
        }
    }

    /** A key plus the registry that resolves it: written as the bare key, read back bound to the registry the
     *  module carries. Final, which is what lets the static gate trust its shape. */
    static final class Handle<K, V> {
        final K key;
        final Registry registry;

        Handle(final K key, final Registry registry) {
            this.key = key;
            this.registry = registry;
        }

        @SuppressWarnings("unchecked")
        V resolve() {
            return registry == null ? null : (V) registry.payloads.get(String.valueOf(key));
        }
    }

    /** Same scalar shape as {@link Handle}, minus {@code final}: a subtype could still be written as a bean,
     *  so nothing about it is provable from the declared type alone. */
    static class OpenHandle {
        final String key;
        final Registry registry;

        OpenHandle(final String key, final Registry registry) {
            this.key = key;
            this.registry = registry;
        }
    }

    /** An opaque type that ALSO declares hooks — the misconfiguration the walker refuses to run silently. */
    static final class Tag {
        final String label;

        Tag(final String label) {
            this.label = label;
        }

        @PostLoad
        void afterLoad() {
            UNEXPECTED.add("Tag.postLoad");
        }

        @PreSave
        void beforeSave() {
            UNEXPECTED.add("Tag.preSave");
        }

        @PostSave
        void afterSave() {
            UNEXPECTED.add("Tag.postSave");
        }
    }

    /** Teaches ONE mapper that these types are scalars. Nothing in EveryConfig knows them; the mapper is the
     *  entire channel, which is what makes this pattern available to any consumer type. */
    static final class HandleModule extends SimpleModule {
        HandleModule(final Registry registry) {
            addSerializer(new HandleSerializer());
            addDeserializer(Handle.class, new HandleDeserializer(registry, null));
            addSerializer(new OpenHandleSerializer());
            addSerializer(new TagSerializer());
            addDeserializer(Tag.class, new TagDeserializer());
        }
    }

    static final class HandleSerializer extends StdSerializer<Handle> {
        HandleSerializer() {
            super(Handle.class);
        }

        @Override
        public void serialize(final Handle value, final JsonGenerator gen, final SerializerProvider provider)
                throws IOException {
            gen.writeString(String.valueOf(value.key));
        }
    }

    static final class HandleDeserializer extends StdDeserializer<Handle> implements ContextualDeserializer {
        private final Registry registry;
        private final Class<?> keyType;

        HandleDeserializer(final Registry registry, final Class<?> keyType) {
            super(Handle.class);
            this.registry = registry;
            this.keyType = keyType;
        }

        /** Recovers K from wherever the Handle is declared — {@code List<Handle<String,X>>} included — which
         *  is the only way one scalar token can be rebuilt into a typed key. */
        @Override
        public JsonDeserializer<?> createContextual(final DeserializationContext ctxt, final BeanProperty property) {
            final JavaType declared = property != null ? property.getType() : ctxt.getContextualType();
            final JavaType handle = locateHandle(declared);
            final Class<?> resolvedKey = handle != null && handle.containedTypeCount() > 0
                    ? handle.containedType(0).getRawClass()
                    : null;
            return new HandleDeserializer(registry, resolvedKey);
        }

        @Override
        public Handle deserialize(final JsonParser p, final DeserializationContext ctxt) throws IOException {
            return new Handle<>(toKey(p.getValueAsString()), registry);
        }

        private Object toKey(final String token) {
            if (keyType == Integer.class) {
                return Integer.valueOf(token);
            }
            if (keyType == UUID.class) {
                return UUID.fromString(token);
            }
            return token;
        }

        private static JavaType locateHandle(final JavaType type) {
            if (type == null) {
                return null;
            }
            if (Handle.class.isAssignableFrom(type.getRawClass())) {
                return type;
            }
            for (int i = 0; i < type.containedTypeCount(); i++) {
                final JavaType found = locateHandle(type.containedType(i));
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
    }

    static final class OpenHandleSerializer extends StdSerializer<OpenHandle> {
        OpenHandleSerializer() {
            super(OpenHandle.class);
        }

        @Override
        public void serialize(final OpenHandle value, final JsonGenerator gen, final SerializerProvider provider)
                throws IOException {
            gen.writeString(value.key);
        }
    }

    static final class TagSerializer extends StdSerializer<Tag> {
        TagSerializer() {
            super(Tag.class);
        }

        @Override
        public void serialize(final Tag value, final JsonGenerator gen, final SerializerProvider provider)
                throws IOException {
            gen.writeString(value.label);
        }
    }

    static final class TagDeserializer extends StdDeserializer<Tag> {
        TagDeserializer() {
            super(Tag.class);
        }

        @Override
        public Tag deserialize(final JsonParser p, final DeserializationContext ctxt) throws IOException {
            return new Tag(p.getValueAsString());
        }
    }

    // ==================== config entities ====================

    /** A nested section with hooks the mapper DOES write as fields — the positive control that proves the
     *  walk really ran while the handles beside it stayed opaque. */
    static final class Meta implements ConfigLifecycle {
        public int revision;
        int postLoads;
        int preSaves;
        int postSaves;

        @Override
        public void postLoad(final ConfigContext context) {
            postLoads++;
        }

        @Override
        public void preSave(final ConfigContext context) {
            preSaves++;
        }

        @Override
        public void postSave(final ConfigContext context) {
            postSaves++;
        }
    }

    static final class HandleOwner implements ConfigLifecycle {
        public String name = "";
        public Meta meta = new Meta();
        public List<Handle<String, String>> handles = new ArrayList<>();
        int postLoads;
        int preSaves;
        int postSaves;

        @Override
        public void postLoad(final ConfigContext context) {
            postLoads++;
        }

        @Override
        public void preSave(final ConfigContext context) {
            preSaves++;
        }

        @Override
        public void postSave(final ConfigContext context) {
            postSaves++;
        }
    }

    static final class TagOwner {
        public List<Tag> tags = new ArrayList<>();
    }

    /** Declares no hook of its own, in every container shape: whatever the gate finds must come from the
     *  handles, so its answer is a statement about them. */
    static final class HandleHolder {
        public Handle<String, String> solo;
        public List<Handle<String, String>> handles = new ArrayList<>();
        public Map<String, Handle<String, String>> byName = new LinkedHashMap<>();
        public List<List<Handle<String, String>>> grouped = new ArrayList<>();
    }

    static final class MixedHolder {
        public List<Handle<String, String>> handles = new ArrayList<>();
        public Meta meta = new Meta();
    }

    static final class OpenHandleHolder {
        public List<OpenHandle> handles = new ArrayList<>();
    }

    // ==================== helpers ====================

    private static ObjectMapper mapperWith(final Registry registry) {
        return new YamlCodec().getObjectMapper().copy().registerModule(new HandleModule(registry));
    }

    private static List<LogRecord> captureWalkerLog(final Runnable body) {
        final Logger log = Logger.getLogger(LifecycleGraphWalker.class.getName());
        final List<LogRecord> records = new ArrayList<>();
        final Handler handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        log.setLevel(Level.ALL);
        log.addHandler(handler);
        try {
            body.run();
        } finally {
            log.removeHandler(handler);
        }
        return records;
    }

    // ==================== tests ====================

    @Test
    @DisplayName("a module-owned type round-trips as one scalar inside a lifecycle entity, still bound to its registry")
    void opaqueElements_roundTripInsideALifecycleOwner(@TempDir final Path dir) {
        final Registry registry = new Registry().put("alpha", "A-payload").put("beta", "B-payload");
        final Codec codec = new YamlCodec(mapperWith(registry));
        final Path file = dir.resolve("handles.yml");

        final HandleOwner owner = new HandleOwner();
        owner.name = "owner";
        owner.meta.revision = 7;
        owner.handles.add(new Handle<String, String>("alpha", registry));
        owner.handles.add(new Handle<String, String>("beta", registry));

        final Config cfg = Config.open(file, codec);
        cfg.setValue("data", owner);
        cfg.save();

        assertEquals(Arrays.asList("alpha", "beta"), cfg.getStringList("data.handles"),
                "each handle must be written as its bare key, never as an embedded object");
        assertEquals(1, owner.preSaves);
        assertEquals(1, owner.postSaves);
        assertEquals(1, owner.meta.preSaves, "a nested bean-serialized section still composes");

        final HandleOwner back = Config.open(file, codec).getValue("data", HandleOwner.class);
        assertEquals("owner", back.name);
        assertEquals(2, back.handles.size());
        assertEquals("A-payload", back.handles.get(0).resolve(), "the handle must come back bound");
        assertEquals("B-payload", back.handles.get(1).resolve());
        assertEquals(1, back.postLoads);
        assertEquals(1, back.meta.postLoads);
        assertEquals(Collections.<String>emptyList(), UNEXPECTED);
    }

    @Test
    @DisplayName("the walk descends nested sections but stops at a module-owned value, never touching what it holds")
    void opaqueValue_isNotDescendedInto(@TempDir final Path dir) {
        final Registry registry = new Registry().put("alpha", "A-payload");
        final Codec codec = new YamlCodec(mapperWith(registry));

        final HandleOwner owner = new HandleOwner();
        owner.handles.add(new Handle<String, String>("alpha", registry));

        final Config cfg = Config.open(dir.resolve("landmine.yml"), codec);
        cfg.setValue("data", owner);
        cfg.save();
        final HandleOwner back = Config.open(dir.resolve("landmine.yml"), codec).getValue("data", HandleOwner.class);

        assertEquals(1, owner.meta.preSaves, "the walk must have run: the nested section's hook fired");
        assertEquals(1, back.meta.postLoads);
        assertEquals(Collections.<String>emptyList(), UNEXPECTED,
                "the registry inside a handle is not part of the tree and must never be visited");
    }

    @Test
    @DisplayName("hooks declared on a module-owned type do not fire, and are warned about once")
    void hooksOnAnOpaqueType_doNotFireAndWarnOnce(@TempDir final Path dir) {
        final Codec codec = new YamlCodec(mapperWith(new Registry()));
        final Config cfg = Config.open(dir.resolve("tags.yml"), codec);
        final TagOwner owner = new TagOwner();
        owner.tags.add(new Tag("red"));
        owner.tags.add(new Tag("blue"));

        final List<LogRecord> records = captureWalkerLog(() -> {
            cfg.setValue("tagged", owner);
            cfg.setValue("tagged", owner);
        });

        assertEquals(Arrays.asList("red", "blue"), cfg.getStringList("tagged.tags"));
        assertEquals(Collections.<String>emptyList(), UNEXPECTED, "an opaque value has no sub-path to fire at");
        assertEquals(1, records.size(), "two saves, one warning: " + records);
        assertTrue(records.get(0).getMessage().contains(Tag.class.getName()), records.get(0).getMessage());
    }

    @Test
    @DisplayName("the gate proves a holder of module-owned values clean only when asked with the mapper")
    void gate_provesTheHolderCleanOnlyWithTheMapper() {
        final ObjectMapper mapper = mapperWith(new Registry());

        assertTrue(LifecycleGraphWalker.mayContainHooks(HandleHolder.class),
                "without a mapper nothing about the handle's shape is knowable");
        assertFalse(LifecycleGraphWalker.mayContainHooks(HandleHolder.class, mapper),
                "with the mapper every handle — solo, in a list, as a map value, in a nested list — is a leaf");
        assertTrue(LifecycleGraphWalker.mayContainHooks(MixedHolder.class, mapper),
                "a real hook elsewhere in the graph still forces the walk");
    }

    @Test
    @DisplayName("the gate keeps walking a non-final custom-serialized type, whose subtype could be a bean")
    void gate_doesNotPromoteANonFinalCustomSerializedType() {
        final ObjectMapper mapper = mapperWith(new Registry());

        assertFalse(SerializedShape.emitsAsBean(mapper, OpenHandle.class), "same scalar shape as Handle");
        assertFalse(SerializedShape.emitsAsBean(mapper, Handle.class));
        assertTrue(SerializedShape.emitsAsBean(mapper, Meta.class), "an ordinary POJO is still a bean");

        assertTrue(LifecycleGraphWalker.mayContainHooks(OpenHandleHolder.class, mapper),
                "a subtype of a non-final type could be written as a bean, exposing its fields again");
        assertFalse(LifecycleGraphWalker.mayContainHooks(HandleHolder.class, mapper),
                "the final counterpart of the same shape IS provable");
    }
}
