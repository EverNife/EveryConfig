package br.com.finalcraft.everyconfig.binding.schema;
import br.com.finalcraft.everyconfig.binding.BindException;

import br.com.finalcraft.everyconfig.annotation.KeyIndex;
import br.com.finalcraft.everyconfig.annotation.Key;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shared reflection helpers for resolving on-disk key names and the {@code @KeyIndex} field of an entity. */
public final class BindingNames {

    /** Types allowed for an {@code @KeyIndex} field — those that round-trip cleanly as a section name. */
    private static final Set<Class<?>> VALID_KEY_INDEX_TYPES = new HashSet<>(Arrays.asList(
            String.class, Integer.class, int.class, Long.class, long.class,
            Double.class, double.class, Float.class, float.class, Short.class, short.class,
            Byte.class, byte.class, Boolean.class, boolean.class, UUID.class));

    /** The declared-field list of each class, resolved once and reused — the field set is stable per class,
     *  so re-walking the hierarchy on every bind/read is wasted reflection. */
    private static final ConcurrentHashMap<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();

    /** The validated single {@code @KeyIndex} field of each class. A class that validates is remembered; an
     *  invalid one re-throws on every call (its failing scan is not cached). */
    private static final ConcurrentHashMap<Class<?>, Field> KEY_INDEX_FIELD = new ConcurrentHashMap<>();

    private BindingNames() {
    }

    /** The on-disk key for a field: {@code @Key} (rename + case) first, then {@code @JsonProperty}, else the name. */
    public static String keyFor(final Field f) {
        final Key k = f.getAnnotation(Key.class);
        if (k != null) {
            final String base = k.value().isEmpty() ? f.getName() : k.value();
            return k.transformCase().apply(base);
        }
        final JsonProperty jp = f.getAnnotation(JsonProperty.class);
        if (jp != null && !jp.value().isEmpty()) {
            return jp.value();
        }
        return f.getName();
    }

    /** Every declared field up the hierarchy, subclass first. Cached per class; the returned list is
     *  unmodifiable (it is shared). */
    public static List<Field> allFields(final Class<?> clazz) {
        return FIELDS.computeIfAbsent(clazz, BindingNames::scanFields);
    }

    private static List<Field> scanFields(final Class<?> clazz) {
        final List<Field> out = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (final Field f : c.getDeclaredFields()) {
                out.add(f);
            }
            c = c.getSuperclass();
        }
        return Collections.unmodifiableList(out);
    }

    /** The single {@code @KeyIndex} field of an entity, validated; throws when absent, duplicated, or wrongly
     *  typed. Cached per class (a failing scan re-throws and is not cached). */
    public static Field requireSingleKeyIndex(final Class<?> clazz) {
        return KEY_INDEX_FIELD.computeIfAbsent(clazz, BindingNames::scanSingleKeyIndex);
    }

    private static Field scanSingleKeyIndex(final Class<?> clazz) {
        Field found = null;
        for (final Field f : allFields(clazz)) {
            if (f.isAnnotationPresent(KeyIndex.class)) {
                if (found != null) {
                    throw new BindException("more than one @KeyIndex field on " + clazz.getName());
                }
                found = f;
            }
        }
        if (found == null) {
            throw new BindException("no @KeyIndex field on " + clazz.getName());
        }
        if (!VALID_KEY_INDEX_TYPES.contains(found.getType())) {
            throw new BindException("@KeyIndex field " + clazz.getSimpleName() + "." + found.getName()
                    + " has unsupported type " + found.getType().getName());
        }
        return found;
    }
}
