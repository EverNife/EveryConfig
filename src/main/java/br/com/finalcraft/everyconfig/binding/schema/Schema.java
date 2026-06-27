package br.com.finalcraft.everyconfig.binding.schema;

import java.util.Collections;
import java.util.Set;

/**
 * The set of keys a bound type declares at one level, used by the merge to decide what is "obsolete". A
 * CLOSED schema is a fixed-property POJO: a key it does not declare is obsolete. An OPEN schema is a
 * free-form node — a {@code Map} field, an {@code @Id}-indexed collection, or an unknown child of a
 * closed node — and nothing in it is ever obsolete, which is what keeps obsolete-pruning from eating
 * user-supplied map entries.
 */
public interface Schema {

    boolean isClosed();

    Set<String> declaredKeys();

    Schema child(String key);

    boolean isObsolete(String key);

    /** The free-form schema: open, declares nothing, every child is open, nothing is ever obsolete. */
    Schema OPEN = new Schema() {
        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public Set<String> declaredKeys() {
            return Collections.emptySet();
        }

        @Override
        public Schema child(final String key) {
            return OPEN;
        }

        @Override
        public boolean isObsolete(final String key) {
            return false;
        }
    };
}
