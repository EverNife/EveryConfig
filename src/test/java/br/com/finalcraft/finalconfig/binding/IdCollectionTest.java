package br.com.finalcraft.finalconfig.binding;

import br.com.finalcraft.finalconfig.annotation.Id;
import br.com.finalcraft.finalconfig.codec.jackson.JsonCodec;
import br.com.finalcraft.finalconfig.config.Config;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** @Id collection indexing: a collection serializes as a section keyed by id (id omitted from the body),
 *  and the section key is the sole authority for the id on read. */
class IdCollectionTest {

    static class Account {
        @Id
        public String name;
        public int balance;

        public Account() {
        }

        Account(final String name, final int balance) {
            this.name = name;
            this.balance = balance;
        }
    }

    static class NoId {
        public int x;
    }

    private final JsonCodec codec = new JsonCodec();

    @Test
    void roundTripPreservesIdSetAndOmitsIdFromBody() {
        final Config c = new Config();
        c.writeIdCollection("accounts", Arrays.asList(new Account("alice", 100), new Account("bob", 50)), codec);

        // Stored key-major; the id lives only in the section name, not the body.
        assertEquals(100, c.getInt("accounts.alice.balance"));
        assertFalse(c.contains("accounts.alice.name"));

        final List<Account> read = c.readIdCollection("accounts", Account.class, codec);
        assertEquals(2, read.size());
        final Set<String> names = new HashSet<>();
        for (final Account a : read) {
            names.add(a.name);
            assertEquals(a.name.equals("alice") ? 100 : 50, a.balance);
        }
        assertEquals(new HashSet<>(Arrays.asList("alice", "bob")), names);
    }

    @Test
    void sectionKeyWinsOverAStrayBodyId() {
        final Config c = new Config((ObjectNode) codec.readTree(
                "{\"accounts\":{\"alice\":{\"name\":\"WRONG\",\"balance\":7}}}"));
        final List<Account> read = c.readIdCollection("accounts", Account.class, codec);
        assertEquals(1, read.size());
        assertEquals("alice", read.get(0).name); // the section key, not the body's "WRONG"
        assertEquals(7, read.get(0).balance);
    }

    @Test
    void rejectsEntityWithoutId() {
        final Config c = new Config();
        assertThrows(BindException.class,
                () -> c.writeIdCollection("xs", Arrays.asList(new NoId()), codec));
    }
}
