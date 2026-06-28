package br.com.finalcraft.everyconfig.binding.schema;
import br.com.finalcraft.everyconfig.binding.BindException;

import br.com.finalcraft.everyconfig.annotation.KeyIndex;
import br.com.finalcraft.everyconfig.annotation.Key;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Shared reflection helpers for resolving on-disk key names and the {@code @KeyIndex} field of an entity. */
public final class BindingNames {

    /** Types allowed for an {@code @KeyIndex} field — those that round-trip cleanly as a section name. */
    private static final Set<Class<?>> VALID_KEY_INDEX_TYPES = new HashSet<>(Arrays.asList(
            String.class, Integer.class, int.class, Long.class, long.class,
            Double.class, double.class, Float.class, float.class, Short.class, short.class,
            Byte.class, byte.class, Boolean.class, boolean.class, UUID.class));

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

    /** Every declared field up the hierarchy, subclass first. */
    public static List<Field> allFields(final Class<?> clazz) {
        final List<Field> out = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (final Field f : c.getDeclaredFields()) {
                out.add(f);
            }
            c = c.getSuperclass();
        }
        return out;
    }

    /** The single {@code @KeyIndex} field of an entity, validated; throws when absent, duplicated, or wrongly typed. */
    public static Field requireSingleKeyIndex(final Class<?> clazz) {
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
