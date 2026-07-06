package br.com.finalcraft.everyconfig.selfdescribe;

import java.util.Map;

/**
 * A type that serializes itself to a config object (a string-keyed map). Like {@link EveryConfigString} but
 * for a structured form. The read half is a static factory found by convention:
 * {@code public static T fromConfigMap(Map<String, Object>)} on the implementing type.
 *
 * <p>The map's values are serialized through the shared mapper, so nested types round-trip. The whole value
 * is stored raw — it is NOT schema-merged as an entity, and its keys carry no per-key comments — because the
 * value, not a bound POJO schema, owns the on-disk shape.
 *
 * @param <T> the implementing type (the convention factory's return type)
 */
public interface EveryConfigMap<T> {

    /** This value's config form as a string-keyed map. */
    Map<String, Object> toConfigMap();
}
