package br.com.finalcraft.everyconfig.selfdescribe;

/**
 * A type with a distinct COMPACT form for when it appears as a collection element, kept separate from its
 * rich solo/field form. Unlike {@link EveryConfigString} (which is compact in every context), a type
 * implementing this keeps whatever solo form it already has — a rich object, typically — and only serializes
 * compact as a list element, and ONLY through the opt-in dynamic API
 * ({@code Config.setElementList}/{@code getElementList}). It is deliberately NOT discovered by the shared
 * mapper, so implementing it never changes the solo form.
 *
 * <p>The write half is this instance method. The read half is a static factory found by convention:
 * {@code public static T fromElementString(String)} on the implementing type.
 *
 * @param <T> the implementing type (the convention factory's return type)
 */
public interface EveryConfigElementString<T> {

    /** This value's compact form for use as a collection element. */
    String toElementString();
}
