package br.com.finalcraft.everyconfig.selfdescribe;

/**
 * A type that serializes itself to a single config string. A type implementing this needs NO central
 * registration: EveryConfig's shared mapper discovers the interface and (de)serializes through it in every
 * context — a solo value, a POJO field, or a collection element.
 *
 * <p>The write half is this instance method. The read half is a static factory found by convention (a Java
 * interface cannot mandate a static method): a {@code public static T fromConfigString(String)} on the
 * implementing type. A type that implements this interface but omits the factory fails fast on the first
 * read. Prefer Jackson's {@code @JsonValue}/{@code @JsonCreator} when you want the read contract
 * compiler-checked; this interface trades that for a single self-typed marker.
 *
 * @param <T> the implementing type (the convention factory's return type)
 */
public interface EveryConfigString<T> {

    /** This value's config form as a single string. */
    String toConfigString();
}
