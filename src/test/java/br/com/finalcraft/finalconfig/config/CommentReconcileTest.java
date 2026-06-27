package br.com.finalcraft.finalconfig.config;

import br.com.finalcraft.finalconfig.codec.jackson.YamlCodec;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Decision-#1 reconciliation: a seed fires only the first time a path is written, and migration moves
 *  data + comment while marking the destination persisted. */
class CommentReconcileTest {

    @TempDir
    Path dir;

    @Data
    @NoArgsConstructor
    public static class EventDTO {
        public String name;
        public UUID id;
        public int count;
        public Instant instant;
        public LocalDate localDate;
        public LocalDateTime localDateTime;
        public Duration duration;
        public Date date;
        public Timestamp timestamp;
        public Optional<String> optionalPresent;
        public Optional<String> optionalEmpty;
        public OptionalInt optInt;
        public String nullName;
        public Map<String, Integer> counts;
    }

    /** 2026-06-25T14:30:00Z == 1_782_397_800 epoch-seconds == 1_782_397_800_000 epoch-millis. */
    static EventDTO sample() {
        EventDTO d = new EventDTO();
        d.name = "Deploy";
        d.id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        d.count = 7;
        d.instant = Instant.parse("2026-06-25T14:30:00Z");
        d.localDate = LocalDate.of(2026, 6, 25);
        d.localDateTime = LocalDateTime.of(2026, 6, 25, 14, 30, 0);
        d.duration = Duration.ofSeconds(90);
        d.date = Date.from(Instant.parse("2026-06-25T14:30:00Z"));
        d.timestamp = Timestamp.from(Instant.parse("2026-06-25T14:30:00Z"));
        d.optionalPresent = Optional.of("yes");
        d.optionalEmpty = Optional.empty();
        d.optInt = OptionalInt.of(5);
        d.nullName = null;
        d.counts = new LinkedHashMap<>();
        d.counts.put("ok", 40);      // non-alphabetical insertion proves the canonical key ordering
        d.counts.put("errors", 2);
        return d;
    }

    /** An arbitrary POJO stored through the dynamic {@code setValue} (not the binding layer) serializes via
     *  the codec's mapper — java.time, Optional/OptionalInt and Map order all survive a save+reopen. */
    @Test
    void richPojoAndCommentsRoundTripThroughTheDynamicApi() {
        final YamlCodec yaml = new YamlCodec();
        final Path file = dir.resolve("test.yml");

        final Config config = Config.open(file, yaml);
        config.setValue("Teste.Hermano", "Teste", "Teste\n\n\n\n\n1");
        config.setDefaultComment("Teste.Hermano", "Default");
        config.setValue("EventDTO", sample());
        config.save();

        final Config reopened = Config.open(file, yaml);
        assertEquals("Teste", reopened.getString("Teste.Hermano"));
        assertEquals("Deploy", reopened.getString("EventDTO.name"));
        assertEquals("00000000-0000-0000-0000-000000000001", reopened.getString("EventDTO.id"));
        assertEquals("2026-06-25T14:30:00Z", reopened.getString("EventDTO.instant"));
        assertEquals("PT1M30S", reopened.getString("EventDTO.duration"));
        assertEquals("yes", reopened.getString("EventDTO.optionalPresent")); // Optional unwrapped
        assertEquals(5, reopened.getInt("EventDTO.optInt"));                  // OptionalInt unwrapped
        assertEquals(40, reopened.getInt("EventDTO.counts.ok"));             // Map insertion order kept
    }

    /** A code-supplied comment is documentation: it is (re)written whenever the path has no comment,
     *  including a pre-existing key that never had one, or one the user deleted. */
    @Test
    void seedAppliesDocumentationToAKeyWithoutAComment() {
        final ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.putObject("a").put("b", 5); // existing key, no comment
        final Config c = new Config(root);

        c.getOrSetDefaultValue("a.b", 99, "documented");

        assertEquals(5, c.getInt("a.b"));                  // existing value kept
        assertEquals("documented", c.getComment("a.b"));   // documentation seeded onto the existing key
    }

    /** An existing comment is never clobbered by a default-comment write. */
    @Test
    void seedDoesNotOverrideAnExistingComment() {
        final ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("k", 1);
        final Config c = new Config(root);
        c.setComment("k", "user comment");
        c.getOrSetDefaultValue("k", 2, "SEED IGNORED");
        assertEquals("user comment", c.getComment("k"));
    }

    /** The two fluent modes: setDefaultComment respects an existing comment, setComment overwrites it. */
    @Test
    void fluentSetDefaultCommentRespectsExistingButSetCommentOverwrites() {
        final Config c = new Config();
        c.setValue("k", 1);

        c.setDefaultComment("k", "first");
        assertEquals("first", c.getComment("k"));   // written: was absent

        c.setDefaultComment("k", "second");
        assertEquals("first", c.getComment("k"));   // kept: already present

        c.setComment("k", "forced");
        assertEquals("forced", c.getComment("k"));  // overwritten
    }

    /** A genuinely new path (absent at load) gets its seed on first write. */
    @Test
    void seedFiresForNewPath() {
        final Config c = new Config();
        c.getOrSetDefaultValue("x.y", 7, "fresh seed");
        assertEquals("fresh seed", c.getComment("x.y"));
    }

    @Test
    void migrateKeyMovesDataAndCommentAndMarksPersisted() {
        final ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("oldName", "val");
        final Config c = new Config(root);
        c.setComment("oldName", "doc for old");

        c.migrateKey("oldName", "newName");

        assertFalse(c.contains("oldName"));
        assertEquals("val", c.getString("newName"));
        assertEquals("doc for old", c.getComment("newName"));

        // newName is now treated as persisted, so a later seed cannot overwrite the migrated comment.
        c.getOrSetDefaultValue("newName", "other", "SEED IGNORED");
        assertEquals("doc for old", c.getComment("newName"));
    }
}
