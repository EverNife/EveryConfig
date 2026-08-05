package br.com.finalcraft.everyconfig.ruleset;

import java.util.Collection;
import java.util.Collections;

/**
 * Supplies the accepted values of a {@link OneOf} whose set is only known while the program runs — world
 * names, loaded modules, registered database ids.
 *
 * <p>An implementation needs a no-argument constructor: one instance is created per provider class and
 * shared. {@link #values()} is called on EVERY evaluation, never cached, because the point of a provider is
 * a set that changes.
 */
public interface OneOfSource {

    /** The accepted values, as they stand right now. */
    Collection<String> values();

    /** The marker default: no provider, so the annotation's own list is the whole set. */
    final class None implements OneOfSource {

        @Override
        public Collection<String> values() {
            return Collections.emptyList();
        }
    }
}
