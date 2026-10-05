package br.com.finalcraft.everyconfig.binding.merge;

import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.codec.jackson.JsonCodec;
import br.com.finalcraft.everyconfig.testdata.Dtos;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct unit tests for the @KeyIndex key-major conversion: detection, write validation, lenient reads. */
class KeyIndexerTest {

    private final JsonCodec codec = new JsonCodec();
    private final ObjectMapper mapper = codec.getObjectMapper();

    @Test
    void mapperWritesAndReadsAKeyIndexFieldKeyMajorAtAnyDepth() throws Exception {
        final Map<String, Dtos.KeyIndexOuterPojo> byName = new LinkedHashMap<>();
        byName.put("first", new Dtos.KeyIndexOuterPojo());

        final JsonNode tree = mapper.valueToTree(byName);
        assertEquals(100, tree.at("/first/inner/accounts/alice/balance").asInt());
        assertTrue(tree.at("/first/inner/accounts/alice/name").isMissingNode()); // the key carries the id
        assertTrue(tree.at("/first/inner/scores").isArray());                    // empty: nothing to key by

        final Map<String, Dtos.KeyIndexOuterPojo> back = mapper.readerFor(
                mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class,
                        Dtos.KeyIndexOuterPojo.class)).readValue(tree);
        assertEquals("bob", back.get("first").inner.accounts.get(1).name);
        assertEquals(50, back.get("first").inner.accounts.get(1).balance);
    }

    @Test
    void aNullElementOfAKeyIndexFieldIsRefused() {
        final Dtos.KeyIndexHolderPojo holder = new Dtos.KeyIndexHolderPojo();
        holder.accounts.add(null);
        assertThrows(BindException.class, () -> KeyIndexedContainers.toTree(mapper, holder));
    }

    @Test
    void aSectionWithNoBodyFailsNamingItsKey() {
        final ObjectNode tree = mapper.createObjectNode();
        tree.putObject("accounts").putNull("alice");
        final JsonMappingException failure = assertThrows(JsonMappingException.class,
                () -> mapper.treeToValue(tree, Dtos.KeyIndexHolderPojo.class));
        assertEquals("alice", failure.getPath().get(failure.getPath().size() - 1).getFieldName());
    }

    @Test
    void isKeyIndexedDetectsTheAnnotation() {
        assertTrue(KeyIndexer.isKeyIndexed(Dtos.KeyIndexAccountPojo.class));
        assertTrue(KeyIndexer.isKeyIndexed(Dtos.KeyIndexUuidPojo.class));
        assertTrue(KeyIndexer.isKeyIndexed(Dtos.DualKeyIndexPojo.class)); // has @KeyIndex (count is checked on write)
        assertFalse(KeyIndexer.isKeyIndexed(Dtos.NoKeyIndexPojo.class));
        assertFalse(KeyIndexer.isKeyIndexed(Dtos.PlainPojo.class));
    }

    @Test
    void toIndexedRejectsABlankId() {
        assertThrows(BindException.class, () -> KeyIndexer.toIndexed(
                Arrays.asList(new Dtos.KeyIndexAccountPojo("   ", 1)), mapper)); // whitespace-only id
    }

    @Test
    void toIndexedRejectsADuplicateId() {
        assertThrows(BindException.class, () -> KeyIndexer.toIndexed(Arrays.asList(
                new Dtos.KeyIndexAccountPojo("dup", 1), new Dtos.KeyIndexAccountPojo("dup", 2)), mapper));
    }

    @Test
    void toIndexedRejectsMoreThanOneKeyIndexField() {
        assertThrows(BindException.class,
                () -> KeyIndexer.toIndexed(Arrays.asList(new Dtos.DualKeyIndexPojo()), mapper));
    }

    @Test
    void fromIndexedIsLenientOnACorruptedSectionKey() {
        // a UUID @KeyIndex whose section key is not a valid UUID: recorded and skipped, never thrown.
        final ObjectNode node = (ObjectNode) codec.readTree("{\"not-a-uuid\":{\"label\":\"x\"}}");
        final List<LoadIssue> issues = new ArrayList<>();
        final List<Dtos.KeyIndexUuidPojo> out =
                KeyIndexer.fromIndexed(node, "nodes", Dtos.KeyIndexUuidPojo.class, mapper, issues);
        assertTrue(out.isEmpty());
        assertFalse(issues.isEmpty());
    }

    @Test
    void roundTripOmitsIdFromBodyAndRestoresItFromTheKey() {
        final ObjectNode indexed = KeyIndexer.toIndexed(Arrays.asList(
                new Dtos.KeyIndexAccountPojo("alice", 100)), mapper);
        assertTrue(indexed.has("alice"));
        assertFalse(indexed.get("alice").has("name")); // id omitted from the body

        final List<LoadIssue> issues = new ArrayList<>();
        final List<Dtos.KeyIndexAccountPojo> back =
                KeyIndexer.fromIndexed(indexed, "accounts", Dtos.KeyIndexAccountPojo.class, mapper, issues);
        assertEquals(1, back.size());
        assertEquals("alice", back.get(0).name); // restored from the section key
        assertEquals(100, back.get(0).balance);
        assertTrue(issues.isEmpty());
        assertFalse(KeyIndexer.isKeyIndexed(UUID.class)); // sanity: a JDK type is not @KeyIndex
    }
}
