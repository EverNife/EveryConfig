package br.com.finalcraft.everyconfig.contract;

import br.com.finalcraft.everyconfig.annotation.PostLoad;
import br.com.finalcraft.everyconfig.annotation.PostSave;
import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.binding.BindOptions;
import br.com.finalcraft.everyconfig.binding.BindResult;
import br.com.finalcraft.everyconfig.binding.ConfigContext;
import br.com.finalcraft.everyconfig.binding.EntityBinder;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.binding.LoadIssueAware;
import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CodecRegistry;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.jackson.JsonCodec;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.LoadStatus;
import br.com.finalcraft.everyconfig.config.MigrationResult;
import br.com.finalcraft.everyconfig.testdata.Dtos;
import br.com.finalcraft.everyconfig.testdata.UltraComplexDTO;
import br.com.finalcraft.everyconfig.config.section.ConfigSection;
import br.com.finalcraft.everyconfig.core.comment.CommentType;
import br.com.finalcraft.everyconfig.rule.RulePolicy;
import br.com.finalcraft.everyconfig.testkit.CodecMatrixTest;
import br.com.finalcraft.everyconfig.rule.TestMax;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The codec-agnostic configuration contract. Every {@code @Test} here exercises behavior that MUST hold
 * for any {@link Codec}; concrete subclasses bind one codec and run the whole body unchanged (one
 * abstract contract, one thin subclass per format). A handful of capability hooks
 * ({@link #supportsComments()}, {@link #supportsNull()}) gate the tests that only make sense for some
 * formats, via JUnit {@link Assumptions} (skip, never fail).
 *
 * <p>The codec hooks, those capability flags and the residual/file harness come from
 * {@link CodecMatrixTest}, which every cross-codec suite in the project runs on; what lives here is the
 * contract itself plus the golden fixtures that lock each emitter's layout byte-for-byte.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class AbstractConfigTest extends CodecMatrixTest {

    // The codec hooks, the capability flags, the residual harness and the file helpers are inherited
    // from CodecMatrixTest; only what the config contract itself needs lives here.

    /** Flip to true to (re)generate the golden fixtures from the current emitter output, then back. */
    static final boolean WRITE_GOLDEN = false;

    /** Root for the per-codec golden fixtures (checked in under test resources). */
    static final Path GOLDEN_ROOT = Paths.get("src", "test", "resources", "golden");

    /**
     * A "cushioned" comment block — a bare marker, a text line, a bare marker — above one key, written in
     * this codec's dialect. Only a comment-lossless codec needs it: it is the fixture that proves the parser
     * stores such a block verbatim instead of reading its leading empty line as a spacing directive.
     */
    protected String cushionedFixture() {
        throw new UnsupportedOperationException(
                "codec '" + fileExtension() + "' claims lossless comments but provides no cushioned fixture");
    }

    /**
     * Populate a representative config exercising the layout each codec must emit stably: a file header,
     * a commented top-level scalar, a nested section with a side comment, a blank line above a scalar list
     * with a per-element comment, and a footer. Codecs ignore the parts they cannot represent (JSON drops
     * every comment; TOML has no per-element list comment), which is exactly what its golden then locks.
     */
    private void populateGoldenFixture(final Config c) {
        c.getCommentTree().setHeader(Arrays.asList("EveryConfig golden fixture", "locks the emitted layout"));
        c.setValue("name", "demo");
        c.setComment("name", "the display name");
        c.setValue("server.host", "localhost");
        c.setValue("server.port", 25565);
        c.setComment("server.port", "listen port", CommentType.SIDE);
        c.setValue("tags", Arrays.asList("alpha", "beta", "gamma"));
        c.setComment("tags.0", "primary tag");
        c.getCommentTree().setBlankLinesBefore("tags", 1);
        c.setValue("accounts", Arrays.asList(   // @KeyIndex -> a key-major section, locked byte-for-byte here
                new Dtos.KeyIndexAccountPojo("alice", 100),
                new Dtos.KeyIndexAccountPojo("bob", 50)));
        c.getCommentTree().setFooter(Arrays.asList("end of fixture"));
    }

    /**
     * Save {@link #populateGoldenFixture} through this codec and assert the emitted text byte-for-byte
     * (LF-normalized) against the checked-in golden for this format. Flip {@link #WRITE_GOLDEN} to rebuild
     * the goldens from the current output.
     */
    protected void assertGoldenLayout() throws IOException {
        final Config c = open();
        populateGoldenFixture(c);
        c.save();
        final String emitted = readText();
        final Path golden = GOLDEN_ROOT.resolve(fileExtension()).resolve("fixture." + fileExtension());
        if (WRITE_GOLDEN) {
            Files.createDirectories(golden.getParent());
            Files.write(golden, emitted.getBytes(codec.charset()));
            return;
        }
        assertTrue(Files.exists(golden), "missing golden fixture: " + golden.toAbsolutePath());
        final String expected = normalize(new String(Files.readAllBytes(golden), codec.charset()));
        assertEquals(expected, emitted, "emitted " + fileExtension() + " drifted from its golden fixture");
    }

    /**
     * Populate the spacing acceptance fixture: every rule at once. The policy floors commented root keys but
     * is suppressed on the document's first entry; an uncommented root key is never floored; a directive
     * wider than the policy wins and reaches past its max depth; and a list element carries its own spacing,
     * which no policy floors. Codecs ignore what they cannot represent, exactly as the golden then locks.
     */
    private void populateSpacingFixture(final Config c) {
        c.withBlankLineBeforeComments(1);
        c.setHeader("EveryConfig spacing fixture", "locks the emitted vertical layout");

        c.setValue("Settings.disablePaymentIfRiding", false);
        c.setValue("Settings.disablePaymentIfCreative", false);
        c.setComment("Settings", "Core toggles.");          // first entry: the policy is suppressed

        c.setValue("Multipliers.money", 2);                 // uncommented: the policy never reaches it
        c.setValue("Multipliers.exp", 3);

        c.setValue("DebugMode.enabled", true);
        c.setComment("DebugMode", "\n\n\nDebug system.\nOnly for troubleshooting.");   // max(3, 1)
        c.setComment("DebugMode.enabled", "Log debug messages on the console.");       // depth 2

        c.setValue("DebugMode.modules.materialInterpreter", true);
        c.setValue("DebugMode.modules.preAction", false);
        c.setComment("DebugMode.modules", "\n\nEnabled debug modules.");               // a directive at depth 2

        c.setValue("Restrictions.blacklist", Arrays.asList("THE_AXE", "SOME_OTHER_ITEM"));
        c.setComment("Restrictions", "\n\n\n\nRestrictions.\nHeavy-handed; read the wiki first.");
        c.setComment("Restrictions.blacklist.0", "the axe that broke everything");
        c.setBlankLinesBefore("Restrictions.blacklist.1", 1);

        c.setFooter("end of fixture");
    }

    /** The spacing twin of {@link #assertGoldenLayout()}, against {@code golden/<ext>/spacing.<ext>}. */
    protected void assertSpacingGoldenLayout() throws IOException {
        final Config c = open();
        populateSpacingFixture(c);
        c.save();
        final String emitted = readText();
        final Path golden = GOLDEN_ROOT.resolve(fileExtension()).resolve("spacing." + fileExtension());
        if (WRITE_GOLDEN) {
            Files.createDirectories(golden.getParent());
            Files.write(golden, emitted.getBytes(codec.charset()));
            return;
        }
        assertTrue(Files.exists(golden), "missing golden fixture: " + golden.toAbsolutePath());
        final String expected = normalize(new String(Files.readAllBytes(golden), codec.charset()));
        assertEquals(expected, emitted, "emitted " + fileExtension() + " drifted from its spacing golden");

        open().save(); // and the layout it locks is a fixed point: a plain re-save changes nothing
        assertEquals(expected, readText(), "the spacing fixture must be a round-trip fixed point");
    }

    // ============================================================================
    //  Typed get / set / coercion
    // ============================================================================

    @Test
    @Order(10)
    @DisplayName("[base] primitives set -> save -> reopen survive with their types")
    void primitives_roundTrip() {
        final Config c = open();
        c.setValue("name", "srv");
        c.setValue("count", 7);
        c.setValue("active", true);
        c.setValue("ratio", Math.PI);
        c.setValue("epoch", 1700000000000L);
        c.save();

        final Config r = open();
        assertEquals("srv", r.getString("name"));
        assertEquals(7, r.getInt("count"));
        assertTrue(r.getBoolean("active"));
        assertEquals(Math.PI, r.getDouble("ratio"), 1e-9);
        assertEquals(1700000000000L, r.getLong("epoch"));
    }

    @Test
    @Order(11)
    @DisplayName("[base] nested dotted path round-trips")
    void nestedPath_roundTrip() {
        final Config c = open();
        c.setValue("server.host", "localhost");
        c.setValue("server.port", 25565);
        c.save();

        final Config r = open();
        assertEquals("localhost", r.getString("server.host"));
        assertEquals(25565, r.getInt("server.port"));
    }

    @Test
    @Order(12)
    @DisplayName("[base] string list round-trips preserving order")
    void stringList_roundTrip() {
        final Config c = open();
        c.setValue("tags", Arrays.asList("alpha", "beta", "gamma"));
        c.save();

        final Config r = open();
        assertEquals(Arrays.asList("alpha", "beta", "gamma"), r.getStringList("tags"));
    }

    @Test
    @Order(13)
    @DisplayName("[base] integer list round-trips, coerced to Integer")
    void integerList_roundTrip() {
        final Config c = open();
        c.setValue("weights", Arrays.asList(1, 2, 3));
        c.save();

        final Config r = open();
        final List<Object> back = r.getList("weights", Object.class);
        assertNotNull(back);
        assertEquals(3, back.size());
        assertEquals(1, ((Number) back.get(0)).intValue());
        assertEquals(3, ((Number) back.get(2)).intValue());
    }

    @Test
    @Order(18)
    @DisplayName("[base] list of POJOs round-trips (TOML uses [[array-of-tables]])")
    void listOfPojos_roundTrip() {
        final Dtos.ListOfPojoPojo.Server s1 = new Dtos.ListOfPojoPojo.Server();
        s1.name = "alpha";
        s1.port = 1;
        final Dtos.ListOfPojoPojo.Server s2 = new Dtos.ListOfPojoPojo.Server();
        s2.name = "beta";
        s2.port = 2;
        final Dtos.ListOfPojoPojo p = new Dtos.ListOfPojoPojo();
        p.title = "cluster";
        p.servers = Arrays.asList(s1, s2);

        final Config c = open();
        c.setValue("cfg", p);
        c.save();

        final Config r = open();
        assertEquals("cluster", r.getString("cfg.title"));
        assertEquals(2, r.getList("cfg.servers", Object.class).size());
        assertEquals("alpha", r.getString("cfg.servers.0.name"));
        assertEquals(1, r.getInt("cfg.servers.0.port"));
        assertEquals("beta", r.getString("cfg.servers.1.name"));
        assertEquals(2, r.getInt("cfg.servers.1.port"));
        Dtos.ListOfPojoPojo loadable = r.getValue("cfg", Dtos.ListOfPojoPojo.class);
        assertEquals(p, loadable);
    }

    @Test
    @Order(14)
    @DisplayName("[base] legacy long-as-string tolerance survives a round-trip")
    void longStoredAsString_parsesBack() {
        final Config c = open();
        c.setValue("asString", "1700000000000");
        c.save();

        final Config r = open();
        assertEquals(1700000000000L, r.getLong("asString"));
    }

    // ============================================================================
    //  Default-value seeding
    // ============================================================================

    @Test
    @Order(20)
    @DisplayName("[base] getOrSetValueIfAbsent seeds when absent, then the file wins")
    void getOrSetDefault_seedsThenFileWins() {
        final Config c = open();
        final int seeded = c.getOrSetValueIfAbsent("port", 8080);
        assertEquals(8080, seeded);
        assertTrue(c.hasNewSeededDefaults());
        c.save();

        final Config r = open();
        final int kept = r.getOrSetValueIfAbsent("port", 9999);
        assertEquals(8080, kept);
    }

    @Test
    @Order(21)
    @DisplayName("[base] getOrSetValueIfAbsent recasts a stored scalar to the default's type")
    void getOrSetDefault_recastsStoredScalar() {
        final Config c = open();
        c.setValue("n", 5L); // stored as long
        final Integer asInt = c.getOrSetValueIfAbsent("n", Integer.valueOf(0));
        assertEquals(Integer.valueOf(5), asInt);
    }

    @Test
    @Order(22)
    @DisplayName("[base] saveIfNewSeededDefaults persists and clears the latch when a default was seeded")
    void saveIfNewSeededDefaults_persistsAndClearsLatch() {
        final Config c = open();
        c.getOrSetValueIfAbsent("port", 8080); // seeds an absent key -> the file shape evolved
        assertTrue(c.hasNewSeededDefaults());
        assertFalse(Files.exists(file()), "seeding is in-memory only; no write yet");

        c.saveIfNewSeededDefaults();

        assertFalse(c.hasNewSeededDefaults(), "latch cleared after the save");
        assertEquals(8080, open().getInt("port"), "the seeded default was persisted");
    }

    @Test
    @Order(23)
    @DisplayName("[base] saveIfNewSeededDefaults is a no-op when nothing was seeded")
    void saveIfNewSeededDefaults_noOpWhenNothingSeeded() {
        final Config c = open();
        c.setValue("port", 8080); // a plain edit marks dirty, NOT a seeded default
        assertFalse(c.hasNewSeededDefaults());

        c.saveIfNewSeededDefaults();

        assertFalse(Files.exists(file()), "no seeded default -> no save was triggered");
    }

    // ============================================================================
    //  Path navigation (in-memory; codec-agnostic by construction)
    // ============================================================================

    @Test
    @Order(30)
    @DisplayName("[base] Java null via setValue deletes the entry")
    void setNull_deletesEntry() {
        final Config c = open();
        c.setValue("x", 5);
        assertTrue(c.contains("x"));
        c.setValue("x", null);
        assertFalse(c.contains("x"));
    }

    @Test
    @Order(31)
    @DisplayName("[base] removeValue drops the entry and keeps its sibling")
    void removeValue_dropsEntry_siblingSurvives() {
        final Config c = open();
        c.setValue("a.b", 1);
        c.setValue("a.c", 2);
        assertTrue(c.removeValue("a.b"));
        assertFalse(c.contains("a.b"));
        assertTrue(c.contains("a.c"));
    }

    @Test
    @Order(32)
    @DisplayName("[base] contains / getKeys report direct children")
    void contains_getKeys_directChildren() {
        final Config c = open();
        c.setValue("a", 1);
        c.setValue("b", 2);
        c.setValue("c.d", 3);
        assertTrue(c.contains("a"));
        final Set<String> keys = c.getKeys();
        assertTrue(keys.contains("a") && keys.contains("b") && keys.contains("c"));
        assertFalse(keys.contains("d"));
    }

    @Test
    @Order(33)
    @DisplayName("[base] getKeys(deep) returns dotted descendant paths")
    void getKeys_deep_dottedPaths() {
        final Config c = open();
        c.setValue("a.b.c", 1);
        final Set<String> deep = c.getKeys("", true);
        assertTrue(deep.contains("a"));
        assertTrue(deep.contains("a.b"));
        assertTrue(deep.contains("a.b.c"));
    }

    @Test
    @Order(34)
    @DisplayName("[base] getConfigSection always returns a view, even for an absent path")
    void getConfigSection_alwaysAView() {
        final Config c = open();
        c.setValue("a.b", 1);
        assertNotNull(c.getConfigSection("a"));
        assertNotNull(c.getConfigSection("missing"));             // a cursor even when absent
        assertFalse(c.getConfigSection("missing").contains("b")); // ...but it sees nothing there
        assertFalse(c.contains("missing"));                       // existence is tested via contains
    }

    @Test
    @Order(37)
    @DisplayName("[base] bracket index path grammar: read, negative index, OOB->null, disambiguation")
    void bracketPath_readNegativeOobDisambiguation() {
        final Config c = open();
        c.setValue("list", Arrays.asList("a", "b", "c"));

        // bracket read matches the dotted-numeric read
        assertEquals("a", c.getString("list[0]"));
        assertEquals("c", c.getString("list[2]"));
        assertEquals("a", c.getString("list.0")); // the dotted form still works

        // a negative index counts from the end
        assertEquals("c", c.getString("list[-1]"));
        assertEquals("b", c.getString("list[-2]"));

        // out of bounds (either direction) reads as absent
        assertNull(c.getValue("list[5]"));
        assertFalse(c.contains("list[5]"));
        assertNull(c.getValue("list[-9]"));

        // [n] only addresses an array element: a bracket index into a scalar is absent
        c.setValue("scalar", 1);
        assertNull(c.getValue("scalar[0]"));

        // disambiguation: a map whose key is literally "2"
        c.getRoot().putObject("m").put("2", "mapval");
        assertEquals("mapval", c.getString("m.2")); // dotted numeric on an object = key lookup
        assertNull(c.getValue("m[2]"));             // bracket index on an object (not an array) = absent
    }

    @Test
    @Order(38)
    @DisplayName("[base] bracket index path grammar: write/remove an existing array element")
    void bracketPath_writeAndRemoveElement() {
        final Config c = open();
        c.setValue("servers", Arrays.asList("s0", "s1", "s2"));

        // replace an element by bracket index (no array growth)
        c.setValue("servers[1]", "S1");
        assertEquals("S1", c.getString("servers[1]"));
        assertEquals("S1", c.getString("servers.1")); // visible through the dotted form too

        // a negative index writes from the end
        c.setValue("servers[-1]", "S2");
        assertEquals("S2", c.getString("servers.2"));

        // remove an element by bracket index; later elements shift down
        assertTrue(c.removeValue("servers[0]"));
        assertEquals(2, c.getList("servers", Object.class).size());
        assertEquals("S1", c.getString("servers[0]"));

        // a nested array element addressed through a dotted prefix
        c.setValue("cfg.ports", Arrays.asList(80, 443));
        c.setValue("cfg.ports[1]", 8443);
        assertEquals(8443, c.getInt("cfg.ports[1]"));
    }

    @Test
    @Order(39)
    @DisplayName("[base] array elements round-trip via [n] and dotted-numeric; a key path through an array fails fast")
    void arrayElement_bracketAndDottedNumeric_roundTrip() {
        final Config c = open();
        c.setValue("list", Arrays.asList("a", "b", "c"));
        c.setValue("list[0]", "A"); // bracket grammar
        c.setValue("list.2", "C");  // dotted-numeric grammar addresses the same element
        c.save();

        final Config r = open();
        assertEquals(Arrays.asList("A", "b", "C"), r.getStringList("list")); // both edits survive the round-trip
        assertEquals("A", r.getString("list[0]"));
        assertEquals("C", r.getString("list.2"));
        // a dotted KEY path running through an array intermediate fails fast on every codec (no silent clobber)
        assertThrows(IllegalArgumentException.class, () -> r.setValue("list.foo", 1));
    }

    // ============================================================================
    //  Unknown-key survival / evolution
    // ============================================================================

    @Test
    @Order(40)
    @DisplayName("[base] an unknown key set by hand survives a save")
    void unknownKey_survivesSave() {
        final Config c = open();
        c.setValue("known", 1);
        c.setValue("legacy.extra", "keep");
        c.save();

        final Config r = open();
        assertEquals("keep", r.getString("legacy.extra"));
    }

    @Test
    @Order(41)
    @DisplayName("[base] binding ignores unknown keys; mergeValue preserves them (tree wins)")
    void evolution_unknownKeysSurviveBindAndMerge() {
        final Config c = open();
        c.setValue("known", "v");
        c.setValue("version", 2);
        c.setValue("obsolete", "old");
        c.setValue("extra", true);

        final Dtos.EvolutionPojo e = c.loadAs(Dtos.EvolutionPojo.class, codec);
        assertEquals("v", e.known);
        assertEquals(2, e.version);

        c.mergeValue("", e); // merge keeps the keys the POJO no longer declares
        assertTrue(c.contains("obsolete"));
        assertTrue(c.contains("extra"));
    }

    // ============================================================================
    //  POJO via the dynamic API + binding
    // ============================================================================

    @Test
    @Order(50)
    @DisplayName("[base] an arbitrary POJO set via setValue round-trips through the codec")
    void setValuePojo_roundTrip() {
        final Dtos.PlainPojo p = new Dtos.PlainPojo();
        p.name = "srv";
        p.count = 7;
        p.active = true;
        p.ratio = 1.5;
        p.epoch = 99L;

        final Config c = open();
        c.setValue("p", p);
        c.save();

        final Config r = open();
        assertEquals("srv", r.getString("p.name"));
        assertEquals(7, r.getInt("p.count"));
        assertTrue(r.getBoolean("p.active"));
    }

    @Test
    @Order(51)
    @DisplayName("[base] loadAs binds the tree and runs @PostLoad")
    void loadAs_bindsAndRunsPostLoad() {
        final Config c = open();
        c.setValue("port", 25565);
        c.setValue("name", "prod");
        final Dtos.PostLoadPojo p = c.loadAs(Dtos.PostLoadPojo.class, codec);
        assertEquals(25565, p.port);
        assertEquals("prod", p.name);
        assertTrue(p.enabled);      // an absent key keeps the POJO's field default
        assertTrue(p.initialized);
    }

    @Test
    @Order(52)
    @DisplayName("[base] setValue(pojo) overrides the subtree; unknown keys do NOT survive")
    void setValuePojo_overridesNeverMerges() {
        final Config c = open();
        c.setValue("legacy", "keep");
        final Dtos.PlainPojo p = new Dtos.PlainPojo();
        p.name = "x";
        p.count = 3;
        c.setValue("", p); // override at the root replaces the whole tree
        assertFalse(c.contains("legacy")); // the unknown key is dropped by the override
        assertEquals("x", c.getString("name"));
        assertEquals(3, c.getInt("count"));
    }

    @Test
    @Order(53)
    @DisplayName("[base] lenient bind keeps the default and records a LoadIssue on a bad value")
    void bind_lenient_keepsDefaultRecordsIssue() {
        final Config c = open();
        c.setValue("count", "NaN");
        final EntityBinder<Dtos.PlainPojo> binder = c.bind(Dtos.PlainPojo.class, codec);
        final Dtos.PlainPojo p = binder.read("");
        assertFalse(binder.lastLoadIssues().isEmpty());
        assertEquals(0, p.count);
    }

    @Test
    @Order(54)
    @DisplayName("[base] strict bind throws BindException on a bad value")
    void bind_strict_throws() {
        final Config c = open();
        c.setValue("count", "NaN");
        final EntityBinder<Dtos.PlainPojo> binder =
                c.bind(Dtos.PlainPojo.class, codec, BindOptions.defaults().withCoercion(BindOptions.Coercion.STRICT));
        assertThrows(BindException.class, () -> binder.read(""));
    }

    @Test
    @Order(55)
    @DisplayName("[base] @PostLoad receives the collected LoadIssue list")
    void postInject_receivesIssuesList() {
        final Config c = open();
        c.setValue("port", 5);
        final Dtos.PostLoadIssuesPojo p = c.loadAs(Dtos.PostLoadIssuesPojo.class, codec);
        assertNotNull(p.seen);
    }

    @Test
    @Order(62)
    @DisplayName("[base] lenient bind isolates each bad value, leaving siblings and good elements bound")
    void lenientBind_isolatesBadValuesPerKey() {
        final Config c = open();
        c.setValue("label", "ok");
        c.setValue("inner.url", "good");
        c.setValue("inner.poolSize", "NaN");          // a bad leaf inside a nested object
        c.setValue("weights", Arrays.asList(1, "x", 3)); // a bad element inside a list

        // a nested bad leaf isolates: its sibling and the top-level field still bind
        final EntityBinder<Dtos.NestedPojo> nb = c.bind(Dtos.NestedPojo.class, codec);
        final Dtos.NestedPojo np = nb.read("");
        assertEquals("ok", np.label);
        assertNotNull(np.inner);
        assertEquals("good", np.inner.url);
        assertFalse(nb.lastLoadIssues().isEmpty());
        assertEquals("inner.poolSize", nb.lastLoadIssues().get(0).key()); // pinned by its dotted path

        // a bad list element isolates: the good elements survive at their indices
        final EntityBinder<Dtos.CollectionsPojo> cb = c.bind(Dtos.CollectionsPojo.class, codec);
        final Dtos.CollectionsPojo cp = cb.read("");
        assertNotNull(cp.weights);
        assertEquals(Integer.valueOf(1), cp.weights.get(0));
        assertEquals(Integer.valueOf(3), cp.weights.get(2));
        assertFalse(cb.lastLoadIssues().isEmpty());
        assertEquals("weights[1]", cb.lastLoadIssues().get(0).key()); // pinned by its indexed path
    }

    @Test
    @Order(63)
    @DisplayName("[base] COMMENT_OUT keeps an obsolete key and (LOSSLESS) stamps a deprecation comment")
    void obsoleteCommentOut_keepsKeyAndMarksDeprecated() throws IOException {
        final Config c = open();
        c.setValue("known", "v");
        c.setValue("version", 2);
        c.setValue("obsolete", "old");

        final EntityBinder<Dtos.EvolutionPojo> binder = c.bind(Dtos.EvolutionPojo.class, codec,
                BindOptions.defaults().withObsoletePolicy(BindOptions.ObsoletePolicy.COMMENT_OUT));
        binder.write("", binder.read(""));

        assertTrue(c.contains("obsolete"));        // data kept on every codec (PRESERVE-like)
        assertEquals("old", c.getString("obsolete"));

        assumeLosslessComments();                  // the deprecation marker only exists on a LOSSLESS codec
        assertNotNull(c.getComment("obsolete"));
        c.save();
        final Config r = open();
        assertEquals("old", r.getString("obsolete"));
        assertNotNull(r.getComment("obsolete"));   // the marker round-trips through the codec text engine
    }

    @Test
    @Order(56)
    @DisplayName("[base] enum round-trips by name; an enum with a field stays a plain name")
    void enum_byName_roundTrip() {
        final Dtos.EnumPojo e = new Dtos.EnumPojo();
        e.mode = Dtos.Mode.SLOW;
        e.transport = Dtos.Transport.EPOLL;

        final Config c = open();
        c.setValue("e", e);
        c.save();

        final Config r = open();
        assertEquals("SLOW", r.getString("e.mode"));
        assertEquals("EPOLL", r.getString("e.transport"));
    }

    @Test
    @Order(57)
    @DisplayName("[base] @Key rename + case transform round-trips both ways")
    void keyRename_roundTrip() {
        final Dtos.KeyNamingPojo k = new Dtos.KeyNamingPojo();
        k.maxPoolSize = 99;
        k.host = "db";
        k.ttlSeconds = 60;

        final Config c = open();
        c.setValue("", k);
        c.save();

        final Config r = open();
        assertEquals(99, r.getInt("max-pool-size"));
        assertEquals("db", r.getString("custom-host"));
        assertEquals(60, r.getInt("ttl_seconds"));

        final Dtos.KeyNamingPojo back = r.loadAs(Dtos.KeyNamingPojo.class, codec);
        assertEquals(99, back.maxPoolSize);
        assertEquals("db", back.host);
    }

    @Test
    @Order(58)
    @DisplayName("[base] polymorphic @JsonTypeInfo round-trips and survives SmartMerge")
    void polymorphic_roundTripSurvivesMerge() {
        final Dtos.PolymorphicPojo p = new Dtos.PolymorphicPojo();
        p.shape = new Dtos.Circle(2.5);

        final Config c = open();
        c.setValue("", p);
        c.save();

        final Config r = open();
        final Dtos.PolymorphicPojo back = r.loadAs(Dtos.PolymorphicPojo.class, codec);
        assertNotNull(back.shape);
        assertTrue(back.shape instanceof Dtos.Circle, "type tag must survive the merge");
        assertEquals(2.5, ((Dtos.Circle) back.shape).radius, 1e-9);
    }

    @Test
    @Order(59)
    @DisplayName("[base] polymorphic discriminator survives a REMOVE-policy binding save")
    void polymorphic_remove_discriminatorSurvives() {
        final Dtos.PolymorphicPojo p = new Dtos.PolymorphicPojo();
        p.shape = new Dtos.Circle(2.5);

        final Config c = open();
        c.bind(Dtos.PolymorphicPojo.class, codec,
                BindOptions.defaults().withObsoletePolicy(BindOptions.ObsoletePolicy.REMOVE))
                .write("", p);
        c.save();

        final Config r = open();
        final Dtos.PolymorphicPojo back = r.loadAs(Dtos.PolymorphicPojo.class, codec);
        assertNotNull(back.shape);
        assertTrue(back.shape instanceof Dtos.Circle, "discriminator must survive REMOVE pruning");
        assertEquals(2.5, ((Dtos.Circle) back.shape).radius, 1e-9);
    }

    // ============================================================================
    //  @Section placement
    // ============================================================================

    @Test
    @Order(60)
    @DisplayName("[base] @Section relocates a flat field to a nested path and back")
    void section_flatToNested_roundTrip() {
        final Dtos.SectionedPojo s = new Dtos.SectionedPojo();
        s.maxSize = 77;
        s.name = "db";

        final Config c = open();
        c.setValue("", s);
        c.save();

        final Config r = open();
        assertEquals(77, r.getInt("database.pool.max-size"));
        assertEquals("db", r.getString("name"));

        final Dtos.SectionedPojo back = r.loadAs(Dtos.SectionedPojo.class, codec);
        assertEquals(77, back.maxSize);
    }

    @Test
    @Order(64)
    @DisplayName("[base] @Section on a nested-POJO field relocates and round-trips")
    void section_nestedPojo_roundTrip() {
        final Dtos.NestedSectionPojo p = new Dtos.NestedSectionPojo();
        p.name = "db";
        p.inner.maxSize = 77;

        final Config c = open();
        c.setValue("", p);
        c.save();

        final Config r = open();
        assertEquals(77, r.getInt("inner.limits.max-size")); // relocated to the nested section path
        assertEquals("db", r.getString("name"));

        final Dtos.NestedSectionPojo back = r.loadAs(Dtos.NestedSectionPojo.class, codec);
        assertEquals(77, back.inner.maxSize);
        assertEquals("db", back.name);
    }

    @Test
    @Order(65)
    @DisplayName("[base] @Section honors ObsoletePolicy.REMOVE (no longer forced to PRESERVE)")
    void section_remove_prunesObsoleteButKeepsRelocated() {
        final Config c = open();
        c.setValue("garbage", true); // an obsolete top-level key
        final Dtos.SectionedPojo s = new Dtos.SectionedPojo();
        s.maxSize = 77;
        s.name = "db";
        c.bind(Dtos.SectionedPojo.class, codec,
                BindOptions.defaults().withObsoletePolicy(BindOptions.ObsoletePolicy.REMOVE))
                .write("", s);

        assertFalse(c.contains("garbage"));                   // obsolete pruned (forced PRESERVE removed)
        assertEquals(77, c.getInt("database.pool.max-size")); // the relocated section value survives
        assertEquals("db", c.getString("name"));
    }

    @Test
    @Order(66)
    @DisplayName("[base] a @Section field seeds its @Comment at the relocated nested path")
    void section_fieldCommentSeededAtNestedPath() {
        assumeComments();
        final Config c = open();
        c.setValue("", new Dtos.SectionCommentedPojo());
        assertEquals(5, c.getInt("db.poolSize"));              // value relocated under the section
        assertEquals("the pool size", c.getComment("db.poolSize")); // comment seeded at that nested path
    }

    // ============================================================================
    //  @KeyIndex collection indexing
    // ============================================================================

    @Test
    @Order(70)
    @DisplayName("[base] setValue auto-indexes an @KeyIndex collection; getList restores ids from section keys")
    void keyIndexCollection_stringId_roundTrip() {
        final List<Dtos.KeyIndexAccountPojo> accounts =
                Arrays.asList(new Dtos.KeyIndexAccountPojo("alice", 100), new Dtos.KeyIndexAccountPojo("bob", 50));

        final Config c = open();
        c.setValue("accounts", accounts); // element type carries @KeyIndex -> stored key-major automatically
        c.save();

        final Config r = open();
        assertTrue(r.contains("accounts.alice"));            // stored as a section keyed by the id
        assertEquals(100, r.getInt("accounts.alice.balance"));
        assertFalse(r.contains("accounts.alice.name"));      // the id lives only in the section key, not the body

        final List<Dtos.KeyIndexAccountPojo> back = r.getList("accounts", Dtos.KeyIndexAccountPojo.class);
        assertEquals(2, back.size());
        final Set<String> names = new HashSet<>();
        for (final Dtos.KeyIndexAccountPojo a : back) {
            names.add(a.name);                               // id restored from the section key
            assertEquals(a.name.equals("alice") ? 100 : 50, a.balance);
        }
        assertEquals(new HashSet<>(Arrays.asList("alice", "bob")), names);
    }

    @Test
    @Order(71)
    @DisplayName("[base] non-String @KeyIndex (UUID) auto-indexes and round-trips via setValue/getList")
    void keyIndexCollection_uuidId_roundTrip() {
        final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000009");
        final List<Dtos.KeyIndexUuidPojo> nodes = Arrays.asList(new Dtos.KeyIndexUuidPojo(id, "n1"));

        final Config c = open();
        c.setValue("nodes", nodes);
        c.save();

        final Config r = open();
        final List<Dtos.KeyIndexUuidPojo> back = r.getList("nodes", Dtos.KeyIndexUuidPojo.class);
        assertEquals(1, back.size());
        assertEquals(id, back.get(0).id); // the UUID section key is cast back to the field type
    }

    @Test
    @Order(72)
    @DisplayName("[base] a collection without @KeyIndex is stored as a plain array, not key-major")
    void keyIndexCollection_withoutKeyIndex_writesPlainArray() {
        final Dtos.PlainPojo a = new Dtos.PlainPojo();
        a.name = "alpha";
        a.count = 1;
        final Dtos.PlainPojo b = new Dtos.PlainPojo();
        b.name = "beta";
        b.count = 2;

        final Config c = open();
        c.setValue("items", Arrays.asList(a, b)); // PlainPojo has no @KeyIndex -> plain array
        c.save();

        final Config r = open();
        assertTrue(r.getNode("items").isArray(), "expected a plain array, got: " + r.getNode("items"));
        final List<Dtos.PlainPojo> back = r.getList("items", Dtos.PlainPojo.class);
        assertEquals(2, back.size());
        assertEquals("alpha", back.get(0).name);
    }

    @Test
    @Order(73)
    @DisplayName("[base] auto-indexing rejects duplicate @KeyIndex values in the collection")
    void keyIndexCollection_duplicateId_throws() {
        final Config c = open();
        assertThrows(BindException.class, () -> c.setValue("accounts", Arrays.asList(
                new Dtos.KeyIndexAccountPojo("dup", 1), new Dtos.KeyIndexAccountPojo("dup", 2))));
    }

    @Test
    @Order(74)
    @DisplayName("[base] auto-indexing rejects a type with more than one @KeyIndex field")
    void keyIndexCollection_multipleKeyIndex_throws() {
        final Config c = open();
        assertThrows(BindException.class,
                () -> c.setValue("xs", Arrays.asList(new Dtos.DualKeyIndexPojo())));
    }

    @Test
    @Order(75)
    @DisplayName("[base] indexed read: the section key is the id authority; getListResult reports a stray body id")
    void keyIndexCollection_sectionKeyWinsAndResultReportsIssue() {
        final Config c = open();
        c.setValue("accounts.alice.name", "WRONG"); // a stray id in the body, disagreeing with the section key
        c.setValue("accounts.alice.balance", 7);

        final BindResult<List<Dtos.KeyIndexAccountPojo>> r =
                c.getListResult("accounts", Dtos.KeyIndexAccountPojo.class);
        assertEquals(1, r.value().size());
        assertEquals("alice", r.value().get(0).name); // the section key wins, not the body's "WRONG"
        assertEquals(7, r.value().get(0).balance);
        assertTrue(r.hasIssues());                     // the disagreement is recorded

        // a clean indexed read has no issues
        c.setValue("accounts", Arrays.asList(new Dtos.KeyIndexAccountPojo("zoe", 9)));
        assertFalse(c.getListResult("accounts", Dtos.KeyIndexAccountPojo.class).hasIssues());
    }

    @Test
    @Order(76)
    @DisplayName("[base] read discriminates by node shape: a @KeyIndex type stored as an array reads as a plain list")
    void keyIndexCollection_readDiscriminatesByNodeShape() {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "alice");   // the id carried in the body (legacy plain-array form)
        row.put("balance", 100);

        final Config c = open();
        c.setValue("accounts", Arrays.asList(row)); // a List of Maps -> a plain array (Maps aren't entities)
        assertTrue(c.getNode("accounts").isArray());

        final List<Dtos.KeyIndexAccountPojo> back = c.getList("accounts", Dtos.KeyIndexAccountPojo.class);
        assertEquals(1, back.size());
        assertEquals("alice", back.get(0).name);    // id read from the body, since the node is an array
        assertEquals(100, back.get(0).balance);
        assertFalse(c.getListResult("accounts", Dtos.KeyIndexAccountPojo.class).hasIssues()); // plain array -> no issues
    }

    @Test
    @Order(77)
    @DisplayName("[base] an empty @KeyIndex collection is stored as a plain empty array")
    void keyIndexCollection_empty_storesPlainArray() {
        final Config c = open();
        c.setValue("accounts", new ArrayList<Dtos.KeyIndexAccountPojo>()); // empty -> no element to classify
        c.save();

        final Config r = open();
        assertTrue(r.getNode("accounts").isArray(), "expected an empty array, got: " + r.getNode("accounts"));
        assertTrue(r.getList("accounts", Dtos.KeyIndexAccountPojo.class).isEmpty());
    }

    @Test
    @Order(78)
    @DisplayName("[base] a corrupted @KeyIndex section entry is skipped leniently; getListResult reports it")
    void keyIndexCollection_corruptedIndexedEntry_lenient() {
        final Config c = open();
        c.setValue("nodes", Arrays.asList(new Dtos.KeyIndexUuidPojo(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), "ok")));
        c.setValue("nodes.not-a-uuid.label", "broken"); // a section key that is not a valid UUID

        final List<Dtos.KeyIndexUuidPojo> back = c.getList("nodes", Dtos.KeyIndexUuidPojo.class);
        assertEquals(1, back.size()); // lenient: the good entry survives, the corrupted one is skipped (no throw)

        final BindResult<List<Dtos.KeyIndexUuidPojo>> r = c.getListResult("nodes", Dtos.KeyIndexUuidPojo.class);
        assertTrue(r.hasIssues()); // the corrupted entry is reported
    }

    // ============================================================================
    //  Lifecycle / files
    // ============================================================================

    @Test
    @Order(80)
    @DisplayName("[base] open an absent file -> ABSENT; the first save creates it")
    void open_absent_thenSaveCreates() {
        final Config c = open();
        Assertions.assertEquals(LoadStatus.ABSENT, c.lastLoadStatus());
        c.setValue("x", 1);
        c.save();
        assertTrue(Files.exists(file()));
    }

    @Test
    @Order(81)
    @DisplayName("[base] open a zero-byte file -> EMPTY")
    void open_emptyFile_emptyStatus() throws IOException {
        Files.write(file(), new byte[0]);
        final Config c = open();
        assertEquals(LoadStatus.EMPTY, c.lastLoadStatus());
    }

    @Test
    @Order(82)
    @DisplayName("[base] open a clean file -> OK")
    void open_cleanFile_okStatus() {
        final Config c = open();
        c.setValue("x", 1);
        c.save();

        final Config r = open();
        assertEquals(LoadStatus.OK, r.lastLoadStatus());
        assertEquals(1, r.getInt("x"));
    }

    @Test
    @Order(83)
    @DisplayName("[base] open a malformed file -> backed up to .bak, starts empty, never throws")
    void open_malformed_backsUpAndStartsEmpty() throws IOException {
        writeText(malformedText());
        final Config c = open();
        assertEquals(LoadStatus.PARSE_FAILED_BACKED_UP, c.lastLoadStatus());
        assertTrue(c.getKeys().isEmpty());
    }

    @Test
    @Order(84)
    @DisplayName("[base] reload picks up an external edit")
    void reload_picksUpExternalEdit() {
        final Config c = open();
        c.setValue("v", "first");
        c.save();

        final Config editor = open();
        editor.setValue("v", "second");
        editor.save();

        c.reload();
        assertEquals("second", c.getString("v"));
    }

    @Test
    @Order(85)
    @DisplayName("[base] save never reads disk first; it overwrites an external edit")
    void save_neverReadsDisk_overwritesExternalEdit() {
        final Config c = open();
        c.setValue("v", "mem");
        c.save();

        final Config editor = open();
        editor.setValue("v", "external");
        editor.setValue("extraExternal", true);
        editor.save();

        c.save(); // dumps in-memory state, clobbering the external edit
        final Config r = open();
        assertEquals("mem", r.getString("v"));
        assertFalse(r.contains("extraExternal"));
    }

    @Test
    @Order(86)
    @DisplayName("[base] saveIfDirty persists a mutation made after the last save")
    void saveIfDirty_persistsAfterMutation() {
        final Config c = open();
        c.saveIfDirty(); // nothing mutated yet -> no write, no file
        assertFalse(Files.exists(file()));

        c.setValue("v", "a");
        c.save();
        c.setValue("v", "b");
        c.saveIfDirty();

        final Config r = open();
        assertEquals("b", r.getString("v"));
    }

    @Test
    @Order(87)
    @DisplayName("[base] saveAsync writes the file")
    void saveAsync_writesFile() throws Exception {
        final Config c = open();
        c.setValue("x", 1);
        c.saveAsync().get(10, TimeUnit.SECONDS);
        assertTrue(Files.exists(file()));

        final Config r = open();
        assertEquals(1, r.getInt("x"));
    }

    @Test
    @Order(88)
    @DisplayName("[base] save creates missing parent directories")
    void save_createsMissingParentDirs() {
        final Path nested = residualDir.resolve("sub").resolve("deep").resolve("config." + fileExtension());
        final Config c = open(nested);
        c.setValue("x", 1);
        c.save();
        assertTrue(Files.exists(nested));
    }

    @Test
    @Order(89)
    @DisplayName("[base] load -> save -> load is data-stable (walking skeleton)")
    void roundTripFidelity_loadSaveLoad_dataStable() {
        final Config c = open();
        c.setValue("server.host", "localhost");
        c.setValue("server.port", 25565);
        c.setValue("tags", Arrays.asList("a", "b"));
        c.setValue("nested.deep.value", 42);
        c.save();

        final Config r1 = open();
        r1.save();

        final Config r2 = open();
        assertEquals("localhost", r2.getString("server.host"));
        assertEquals(25565, r2.getInt("server.port"));
        assertEquals(Arrays.asList("a", "b"), r2.getStringList("tags"));
        assertEquals(42, r2.getInt("nested.deep.value"));
    }

    // ============================================================================
    //  Comments (gated on comment fidelity)
    // ============================================================================

    @Test
    @Order(100)
    @DisplayName("[base] a block comment survives a round-trip")
    void comment_blockRoundTrip() {
        assumeComments();
        final Config c = open();
        c.setValue("port", 8080);
        c.setComment("port", "the listen port");
        c.save();

        final Config r = open();
        assertEquals("the listen port", r.getComment("port"));
    }

    @Test
    @Order(101)
    @DisplayName("[base] setDefaultComment does not override an existing comment")
    void setDefaultComment_doesNotOverride() {
        assumeComments();
        final Config c = open();
        c.setValue("k", 1);
        c.setComment("k", "user");
        c.setDefaultComment("k", "seed");
        c.save();

        final Config r = open();
        assertEquals("user", r.getComment("k"));
    }

    @Test
    @Order(102)
    @DisplayName("[base] @Comment is seeded from the annotation on merge")
    void commentAnnotation_seededOnMerge() {
        assumeComments();
        final Config c = open();
        c.setValue("", new Dtos.CommentedPojo());
        c.save();

        final Config r = open();
        assertEquals("The JDBC connection url", r.getComment("jdbcUrl"));
    }

    @Test
    @Order(103)
    @DisplayName("[base] comment ops never corrupt data; on NONE fidelity no comment is emitted")
    void comments_noOpSafeOnNoneFidelity() {
        final Config c = open();
        c.setValue("port", 8080);
        c.setComment("port", "doc");
        c.save();

        final Config r = open();
        assertEquals(8080, r.getInt("port")); // data is intact for every codec
        if (supportsComments()) {
            assertEquals("doc", r.getComment("port"));
        } else {
            assertNull(r.getComment("port"));
        }
    }

    @Test
    @Order(104)
    @DisplayName("[base] @Comment OVERRIDE replaces an existing comment; SET_IF_ABSENT keeps it; class @Comment seeds the header")
    void commentModes_overrideReplaces_setIfAbsentPreserves_classHeader() {
        assumeComments();
        final Config c = open();
        c.setComment("jdbcUrl", "OLD URL DOC");      // jdbcUrl's @Comment defaults to OVERRIDE
        c.setComment("retries", "USER WROTE THIS");  // retries' @Comment is SET_IF_ABSENT

        c.mergeValue("", new Dtos.CommentedPojo());   // merge: annotation-aware, seeds/overrides comments in place

        assertEquals("The JDBC connection url", c.getComment("jdbcUrl")); // OVERRIDE replaced the old one
        assertEquals("USER WROTE THIS", c.getComment("retries"));        // SET_IF_ABSENT kept the user's
        assertTrue(c.getCommentTree().getHeader().contains("Database settings")); // class @Comment -> header
    }

    @Test
    @Order(123)
    @DisplayName("[base] @Comment on a nested-POJO field is seeded at its sub-path")
    void nestedFieldComment_seededAtSubPath() {
        assumeComments();
        final Config c = open();
        c.mergeValue("", new Dtos.NestedCommentedPojo());
        c.save();

        final Config r = open();
        assertEquals("Snapshot on death.", r.getComment("triggers.onDeath"));
        assertEquals("Snapshot on join.", r.getComment("triggers.onJoin"));
        assertEquals("Snapshots kept per trigger.", r.getComment("retention.policy.maxPerTrigger")); // @Section-relocated
        assertNull(r.getComment("triggers.onLeave"));                   // no @Comment -> nothing seeded
        assertEquals(0, r.getInt("retention.policy.maxPerTrigger"));    // value round-trips at the relocated path
    }

    @Test
    @Order(124)
    @DisplayName("[base] nested @Comment honors OVERRIDE vs SET_IF_ABSENT at depth")
    void nestedFieldComment_modesInDepth() {
        assumeComments();
        final Config c = open();
        c.setComment("triggers.onDeath", "USER DEATH DOC"); // onDeath is OVERRIDE -> replaced
        c.setComment("triggers.onJoin", "USER JOIN DOC");   // onJoin is SET_IF_ABSENT -> user's kept

        c.mergeValue("", new Dtos.NestedCommentedPojo());

        assertEquals("Snapshot on death.", c.getComment("triggers.onDeath")); // OVERRIDE replaced
        assertEquals("USER JOIN DOC", c.getComment("triggers.onJoin"));       // SET_IF_ABSENT preserved
    }

    @Test
    @Order(125)
    @DisplayName("[base] recursive seeding comments the same nested type under two sibling fields")
    void nestedFieldComment_diamond_bothBranchesSeeded() {
        assumeComments();
        final Config c = open();
        c.mergeValue("", new Dtos.DiamondCommentedPojo());
        c.save();

        final Config r = open();
        assertEquals("A leaf value.", r.getComment("left.value"));
        assertEquals("A leaf value.", r.getComment("right.value")); // class-on-path guard: the diamond is not pruned
    }

    @Test
    @Order(126)
    @DisplayName("[base] recursive comment seeding terminates on a self-referential type")
    void nestedFieldComment_selfReferentialIsCycleSafe() {
        assumeComments();
        final Config c = open();
        c.mergeValue("", new Dtos.SelfRefCommentedPojo()); // must not recurse forever
        assertEquals("The node label.", c.getComment("label"));
    }

    // ============================================================================
    //  File header / footer (Config façade API)
    // ============================================================================

    @Test
    @Order(105)
    @DisplayName("[base] header and the first key's own comment round-trip without confusion")
    void header_andFirstKeyComment_doNotConfuse() {
        assumeComments();
        final Config c = open();
        c.setHeader("My File Header", "second line");
        c.setValue("alpha", 1);
        c.setComment("alpha", "the alpha key");
        c.setValue("beta", 2);
        c.save();

        final Config r = open();
        assertEquals(Arrays.asList("My File Header", "second line"), r.getHeader());
        assertEquals("the alpha key", r.getComment("alpha")); // NOT swallowed into the header
    }

    @Test
    @Order(106)
    @DisplayName("[base] a first-key comment with no header does not become the header")
    void firstKeyComment_withoutHeader_staysOnTheKey() {
        assumeComments();
        final Config c = open();
        c.setValue("alpha", 1);
        c.setComment("alpha", "the alpha key");
        c.setValue("beta", 2);
        c.save();

        final Config r = open();
        assertTrue(r.getHeader().isEmpty(), "no header expected, got: " + r.getHeader());
        assertEquals("the alpha key", r.getComment("alpha"));
    }

    @Test
    @Order(107)
    @DisplayName("[base] a multi-line header with internal blank lines round-trips intact")
    void header_multiLineWithInternalBlanks_roundTrips() {
        assumeComments();

        final String HEADER = "# -----------------------------------------------------\n" +
                "#                                                      \n" +
                "#         _____ _____              __ _                \n" +
                "#        |  ___/  __ \\            / _(_)               \n" +
                "#        | |__ | /  \\/ ___  _ __ | |_ _  __ _          \n" +
                "#        |  __|| |    / _ \\| '_ \\|  _| |/ _` |         \n" +
                "#        | |___| \\__/\\ (_) | | | | | | | (_| |         \n" +
                "#        \\____/ \\____/\\___/|_| |_|_| |_|\\__, |         \n" +
                "#                                        __/ |         \n" +
                "#                                       |___/          \n" +
                "#                                                      \n" +
                "#                                                      \n" +
                "#                                                      \n" +
                "#               EverNife's Config Manager              \n" +
                "#                                                      \n" +
                "#                                                      \n" +
                "# -----------------------------------------------------";

        // HEADER above is the on-disk form (with the '#' marker + trailing box padding). setHeader takes the
        // PREFIX-LESS content (the codec adds its own '#'/'//' marker on emit), so strip the leading "# " here.
        final List<String> banner = new ArrayList<String>();
        for (final String line : HEADER.split("\n", -1)) {
            banner.add(line.startsWith("# ") ? line.substring(2) : line);
        }

        final Config c = open();
        c.setHeader(String.join("\n", banner));
        assertEquals(banner, c.getHeader()); // in memory the lines are stored verbatim (box padding and all)
        c.setValue("settings", 1);
        c.save();

        // On read every comment line is captured via String.trim(), so TRAILING whitespace is dropped on all
        // comment-aware codecs (the leading ASCII-art indentation survives, shielded by the marker). So the
        // round-tripped header equals the banner with each line right-trimmed (the all-spaces box lines -> "").
        final Config r = open();
        final List<String> trimmed = new ArrayList<String>();
        for (final String line : banner) {
            trimmed.add(rstrip(line));
        }
        assertEquals(trimmed, r.getHeader());
    }

    /** Remove trailing whitespace (the comment parser trims it on read; there is no Java-8 String API for it). */
    private static String rstrip(final String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    @Test
    @Order(108)
    @DisplayName("[base] footer round-trips and stays distinct from the last key's comment")
    void footer_roundTrips_distinctFromLastKeyComment() {
        assumeComments();
        final Config c = open();
        c.setValue("alpha", 1);
        c.setValue("omega", 2);
        c.setComment("omega", "the last key");
        c.setFooter("end of file", "bye");
        c.save();

        final Config r = open();
        assertEquals(Arrays.asList("end of file", "bye"), r.getFooter());
        assertEquals("the last key", r.getComment("omega"));
    }

    @Test
    @Order(109)
    @DisplayName("[base] a header with no commented keys round-trips and does not leak onto a key")
    void header_aloneWithNoKeyComments_roundTrips() {
        assumeComments();
        final Config c = open();
        c.setHeader("solo header");
        c.setValue("alpha", 1);
        c.setValue("beta", 2);
        c.save();

        final Config r = open();
        assertEquals(Arrays.asList("solo header"), r.getHeader());
        assertNull(r.getComment("alpha")); // the header did not bleed onto the first key
    }

    @Test
    @Order(110)
    @DisplayName("[base] header precedence: setDefaultHeader yields; class @Comment OVERRIDE wins; SET_IF_ABSENT yields")
    void header_precedence_defaultAndClassComment() {
        assumeComments();
        // (a) setDefaultHeader respects an existing header
        final Config a = open();
        a.setHeader("USER HEADER");
        a.setDefaultHeader("DEFAULT");
        assertEquals(Arrays.asList("USER HEADER"), a.getHeader());

        // (b) a class-level @Comment(OVERRIDE) overwrites a header set via the API (merge keeps the header
        // overlay in place, so header precedence is exercised — an override would wipe it unconditionally)
        final Config b = open();
        b.setHeader("USER HEADER");
        b.mergeValue("", new Dtos.ClassHeaderOverridePojo());
        assertEquals(Arrays.asList("Generated header"), b.getHeader());

        // (c) a class-level @Comment(SET_IF_ABSENT) respects an existing header
        final Config d = open();
        d.setHeader("USER HEADER");
        d.mergeValue("", new Dtos.CommentedPojo());
        assertEquals(Arrays.asList("USER HEADER"), d.getHeader());
    }

    @Test
    @Order(111)
    @DisplayName("[base] header/footer are a silent no-op on a NONE-fidelity codec, data intact")
    void headerFooter_noOpOnNoneFidelity() {
        final Config c = open();
        c.setHeader("should not persist");
        c.setFooter("nor this");
        c.setValue("port", 8080);
        c.save();

        final Config r = open();
        assertEquals(8080, r.getInt("port")); // data intact for every codec
        if (supportsComments()) {
            assertEquals(Arrays.asList("should not persist"), r.getHeader());
            assertEquals(Arrays.asList("nor this"), r.getFooter());
        } else {
            assertTrue(r.getHeader().isEmpty());
            assertTrue(r.getFooter().isEmpty());
        }
    }

    @Test
    @Order(112)
    @DisplayName("[base] a comment's trailing whitespace is dropped on emit, so write/read/write is stable")
    void commentTrailingWhitespace_droppedOnEmit() throws IOException {
        assumeComments();
        final Config c = open();
        c.setValue("port", 8080);
        c.setComment("port", "the listen port   "); // trailing whitespace in the in-memory comment
        c.save();
        final String artifactA = readText();
        assertTrue(artifactA.contains("the listen port"), artifactA);
        assertFalse(artifactA.contains("the listen port "), "the emitted comment kept trailing whitespace");

        // byte-for-byte write -> read -> write stability is a LOSSLESS guarantee (the first write is canonical,
        // so re-saving after a read reproduces an identical file instead of drifting).
        assumeLosslessComments();
        final Config r = open();
        r.save();
        assertEquals(artifactA, readText());
    }

    // ============================================================================
    //  Special scalars / encoding
    // ============================================================================

    @Test
    @Order(110)
    @DisplayName("[base] unicode (accents / CJK / emoji) round-trips")
    void unicode_roundTrip() {
        final String value = "héllo – 日本語 – 🚀";
        final Config c = open();
        c.setValue("u", value);
        c.save();

        final Config r = open();
        assertEquals(value, r.getString("u"));
    }

    @Test
    @Order(111)
    @DisplayName("[base] a non-ASCII key round-trips")
    void nonAsciiKey_roundTrip() {
        final Config c = open();
        c.setValue("café", "x");
        c.save();

        final Config r = open();
        assertEquals("x", r.getString("café"));
    }

    @Test
    @Order(112)
    @DisplayName("[base] an explicit null survives a round-trip")
    void nullValue_survives() {
        assumeNullSupported();
        final Config c = open();
        c.getRoot().putNull("x");
        c.save();

        final Config r = open();
        assertTrue(r.contains("x"));
        assertNull(r.getValue("x"));
    }

    @Test
    @Order(113)
    @DisplayName("[base] empty string and null are distinct after a round-trip")
    void emptyStringVsNull_distinct() {
        assumeNullSupported();
        final Config c = open();
        c.setValue("empty", "");
        c.getRoot().putNull("nul");
        c.save();

        final Config r = open();
        assertEquals("", r.getString("empty"));
        assertTrue(r.contains("nul"));
        assertNull(r.getValue("nul"));
    }

    @Test
    @Order(114)
    @DisplayName("[base] java.time values round-trip as ISO-8601")
    void temporal_roundTrip() {
        final Dtos.TemporalPojo t = new Dtos.TemporalPojo();
        t.instant = Instant.parse("2026-06-25T14:30:00Z");
        t.date = LocalDate.of(2026, 6, 25);
        t.dateTime = LocalDateTime.of(2026, 6, 25, 14, 30, 0);
        t.dur = Duration.ofSeconds(90);

        final Config c = open();
        c.setValue("t", t);
        c.save();

        final Config r = open();
        assertEquals("2026-06-25T14:30:00Z", r.getString("t.instant"));
        assertEquals("2026-06-25", r.getString("t.date"));
        assertEquals("PT1M30S", r.getString("t.dur"));
    }

    @Test
    @Order(115)
    @DisplayName("[base] Optional / OptionalInt unwrap through the codec mapper")
    void optional_roundTrip() {
        final Dtos.OptionalPojo o = new Dtos.OptionalPojo();
        o.present = Optional.of("yes");
        o.empty = Optional.empty();
        o.num = OptionalInt.of(5);

        final Config c = open();
        c.setValue("o", o);
        c.save();

        final Config r = open();
        assertEquals("yes", r.getString("o.present"));
        assertEquals(5, r.getInt("o.num"));
    }

    @Test
    @Order(116)
    @DisplayName("[base] big numbers round-trip without precision loss")
    void bigNumbers_roundTrip() {
        final Dtos.NumericEdgePojo n = new Dtos.NumericEdgePojo();
        n.bigLong = 9000000000000000000L;
        n.port = 25565;
        n.pi = Math.PI;
        n.huge = new BigInteger("123456789012345678901234567890");
        n.precise = new BigDecimal("3.141592653589793238462643383279");

        final Config c = open();
        c.setValue("n", n);
        c.save();

        final Config r = open();
        assertEquals(9000000000000000000L, r.getLong("n.bigLong"));
        assertEquals(25565, r.getInt("n.port"));
        assertEquals("123456789012345678901234567890", r.getString("n.huge"));
    }

    // ============================================================================
    //  Codec identity / registry (infra)
    // ============================================================================

    @Test
    @Order(120)
    @DisplayName("[base] the codec reports the expected identity")
    void codec_reportsExpectedIdentity() {
        assertNotNull(codec.formatId());
        assertEquals(fidelity(), codec.commentFidelity());
        assertTrue(Arrays.asList(codec.fileExtensions()).contains(fileExtension()),
                "declared extensions must include " + fileExtension());
    }

    @Test
    @Order(121)
    @DisplayName("[base] CodecRegistry resolves this codec by its extension")
    void codecRegistry_resolvesThisExtension() {
        final CodecRegistry registry = CodecRegistry.defaults();
        registry.register(codec);
        final Codec resolved = registry.byExtension(fileExtension());
        assertEquals(codec.formatId(), resolved.formatId());
    }

    @Test
    @Order(122)
    @DisplayName("[base] file extensions are lowercase and dot-free")
    void codec_extensionsAreCleanTokens() {
        final String[] exts = codec.fileExtensions();
        assertTrue(exts.length > 0);
        for (final String ext : exts) {
            assertEquals(ext.toLowerCase(), ext);
            assertFalse(ext.startsWith("."));
        }
        // sanity: a stable, repeatable array
        assertArrayEquals(exts, newCodec().fileExtensions());
    }

    // ============================================================================
    //  Coverage carried over from the standalone config tests (now codec-generic)
    // ============================================================================

    @Test
    @Order(15)
    @DisplayName("[base] getString on a list joins elements with newlines")
    void getString_onList_joinsWithNewline() {
        final Config c = open();
        c.setValue("message", Arrays.asList("line1", "line2", "line3"));
        assertEquals("line1\nline2\nline3", c.getString("message"));
        assertEquals(Arrays.asList("line1", "line2", "line3"), c.getStringList("message"));
    }

    @Test
    @Order(16)
    @DisplayName("[base] quoted numeric strings coerce (1.0->1, empty->def, int, double)")
    void coercion_quotedNumericStrings_tolerated() {
        final Config c = open();
        c.setValue("ln", "1700000000000"); // quoted long
        c.setValue("lf", "1.0");           // long written with a trailing .0
        c.setValue("le", "");              // empty string
        c.setValue("port", "25565");       // quoted int
        c.setValue("ratio", "3.14");       // quoted double

        assertEquals(1700000000000L, c.getLong("ln"));
        assertEquals(1L, c.getLong("lf"));
        assertEquals(7L, c.getLong("le", 7L)); // empty -> default
        assertEquals(25565, c.getInt("port"));
        assertEquals(3.14, c.getDouble("ratio"), 1e-9);
    }

    @Test
    @Order(17)
    @DisplayName("[base] lists + a map round-trip, preserving Map insertion order")
    void collectionsAndMap_roundTrip() {
        final Dtos.CollectionsPojo p = new Dtos.CollectionsPojo();
        p.tags = Arrays.asList("x", "y");
        p.weights = Arrays.asList(10, 20, 30);
        p.limits = Dtos.orderedLimits(); // ok=40, errors=2, warnings=7 (non-alphabetical insertion)

        final Config c = open();
        c.setValue("data", p);
        c.save();

        final Config r = open();
        assertEquals(Arrays.asList("x", "y"), r.getStringList("data.tags"));
        assertEquals(40, r.getInt("data.limits.ok"));
        assertEquals(2, r.getInt("data.limits.errors"));
        assertEquals(7, r.getInt("data.limits.warnings"));
        assertEquals(Arrays.asList("ok", "errors", "warnings"),
                new ArrayList<String>(r.getConfigSection("data.limits").getKeys())); // insertion order kept
    }

    @Test
    @Order(35)
    @DisplayName("[base] absent vs explicit-null vs real value are distinct")
    void trichotomy_absentExplicitNullValue() {
        final Config c = open();
        c.setValue("present", 5);
        c.getRoot().putNull("explicitNull"); // present, but null

        assertFalse(c.contains("missing"));
        assertNull(c.getValue("missing"));
        assertEquals(9, c.getInt("missing", 9));

        assertTrue(c.contains("explicitNull"));
        assertNull(c.getValue("explicitNull"));
        assertEquals(9, c.getInt("explicitNull", 9)); // null flattens to the default

        assertTrue(c.contains("present"));
        assertEquals(5, c.getInt("present"));
    }

    @Test
    @Order(36)
    @DisplayName("[base] a ConfigSection delegates to the owning Config with the sub-path prefixed")
    void configSection_delegatesToOwningConfig() {
        final Config c = open();
        final ConfigSection db = c.getConfigSection("database");
        db.setValue("url", "jdbc:postgresql://localhost/db");
        db.setValue("pool.size", 10);

        assertEquals("jdbc:postgresql://localhost/db", c.getString("database.url"));
        assertEquals(10, c.getInt("database.pool.size"));
        assertEquals("database", db.getSectionKey());
        assertEquals(10, db.getInt("pool.size"));
        assertTrue(db.contains("url"));
        assertTrue(db.getKeys().contains("url"));
    }

    @Test
    @Order(42)
    @DisplayName("[base] getRoot is a live escape hatch; a raw-set unknown key survives API mutations")
    void getRoot_escapeHatch_unknownSurvives() {
        final Config c = open();
        c.setValue("known", 1);
        c.getRoot().put("unknown", "raw"); // mutate the canonical tree directly
        assertTrue(c.contains("unknown"));
        assertEquals("raw", c.getString("unknown"));

        c.setValue("another", 2);
        assertTrue(c.contains("unknown"));
        assertEquals(1, c.getInt("known"));
        assertEquals(2, c.getInt("another"));
    }

    @Test
    @Order(90)
    @DisplayName("[base] reload on a parse failure keeps the live tree and flags divergence")
    void reload_parseFail_keepsTreeFlagsDivergence() throws IOException {
        final Config c = open();
        c.setValue("a", 1);
        c.save();

        writeText(malformedText()); // a half-written / corrupt file appears on disk
        c.reload();

        assertEquals(1, c.getInt("a")); // the live tree is kept
        assertEquals(LoadStatus.PARSE_FAILED_KEPT, c.lastLoadStatus());
        assertTrue(c.isDivergedFromDisk());
    }

    @Test
    @Order(91)
    @Tag("watcher")
    @DisplayName("[base] an external edit triggers auto-reload and the onReload callback")
    void autoReload_externalEdit_firesCallback() throws Exception {
        final Config c = open();
        c.setValue("a", 1);
        c.save();

        final AtomicInteger reloads = new AtomicInteger();
        c.onReload(reloads::incrementAndGet).withAutoReload(Duration.ofMillis(40));

        final Config editor = open();
        editor.setValue("a", 12345); // a differently-sized value so the fingerprint reliably differs
        editor.save();

        assertTrue(waitUntil(() -> c.getInt("a", 0) == 12345, 10000L),
                "watcher should have reloaded the external edit");
        assertEquals(12345, c.getInt("a"));
        assertTrue(reloads.get() >= 1, "onReload callback should have fired");
    }

    @Test
    @Order(104)
    @DisplayName("[base] migrateKey moves data + comment and marks the destination persisted")
    void migrateKey_movesDataAndComment() {
        final Config c = open();
        c.setValue("oldName", "val");
        c.setComment("oldName", "doc for old");

        c.migrateKey("oldName", "newName");

        assertFalse(c.contains("oldName"));
        assertEquals("val", c.getString("newName"));
        assertEquals("doc for old", c.getComment("newName"));

        // a later seed cannot overwrite the migrated (authoritative) comment
        c.getOrSetValueIfAbsent("newName", "other", "SEED IGNORED");
        assertEquals("doc for old", c.getComment("newName"));
    }

    @Test
    @Order(107)
    @DisplayName("[base] per-element comments on a scalar list survive a round-trip")
    void listItemComments_roundTrip() {
        assumeListItemComments();
        final Config c = open();
        c.setValue("tags", Arrays.asList("alpha", "beta", "gamma"));
        c.setComment("tags.0", "the primary tag");
        c.setComment("tags.2", "fallback");
        c.save();

        final Config r = open();
        assertEquals(Arrays.asList("alpha", "beta", "gamma"), r.getStringList("tags")); // data intact
        assertEquals("the primary tag", r.getComment("tags.0"));
        assertNull(r.getComment("tags.1"));
        assertEquals("fallback", r.getComment("tags.2"));
    }

    @Test
    @Order(106)
    @DisplayName("[base] migrateKey carries the comments of descendant paths too")
    void migrateKey_movesDescendantComments() {
        final Config c = open();
        c.setValue("old.host", "h");
        c.setValue("old.nested.deep", 1);
        c.setComment("old", "section doc");
        c.setComment("old.host", "the host");
        c.setComment("old.nested.deep", "deep doc");

        c.migrateKey("old", "renamed");

        // data moved
        assertFalse(c.contains("old.host"));
        assertEquals("h", c.getString("renamed.host"));
        assertEquals(1, c.getInt("renamed.nested.deep"));

        // the whole comment subtree moved with it
        assertEquals("section doc", c.getComment("renamed"));
        assertEquals("the host", c.getComment("renamed.host"));
        assertEquals("deep doc", c.getComment("renamed.nested.deep"));
        assertNull(c.getComment("old.host")); // the source comments are gone
    }

    @Test
    @Order(105)
    @DisplayName("[base] getOrSetValueIfAbsent seeds a comment when absent but never overrides one")
    void getOrSetValueIfAbsent_seedsCommentWhenAbsent() {
        final Config c = open();
        c.getOrSetValueIfAbsent("a.timeout", 30, "request timeout in seconds");
        assertEquals("request timeout in seconds", c.getComment("a.timeout"));

        c.setComment("a.retries", "USER AUTHORED");
        c.getOrSetValueIfAbsent("a.retries", 3, "SEED IGNORED");
        assertEquals("USER AUTHORED", c.getComment("a.retries"));
        assertEquals(3, c.getInt("a.retries"));
    }

    // ============================================================================
    //  Scenario matrix: collections, encoding edges, binder surface, lifecycle
    // ============================================================================

    @Test
    @Order(130)
    @DisplayName("[base] an empty list round-trips as an empty list")
    void emptyList_roundTripsEmpty() {
        final Config c = open();
        c.setValue("items", new ArrayList<Object>());
        c.save();

        final Config r = open();
        final List<Object> back = r.getList("items", Object.class);
        assertNotNull(back);
        assertEquals(0, back.size());
    }

    @Test
    @Order(131)
    @DisplayName("[base] a list with duplicate values keeps the duplicates and their order")
    void duplicateValueList_preservesDuplicatesAndOrder() {
        final Config c = open();
        c.setValue("dups", Arrays.asList(1, 1, 2, 2, 2, 3));
        c.save();

        final Config r = open();
        final List<Object> back = r.getList("dups", Object.class);
        assertEquals(6, back.size());
        final int[] expected = {1, 1, 2, 2, 2, 3};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], ((Number) back.get(i)).intValue());
        }
    }

    @Test
    @Order(132)
    @DisplayName("[base] a deeply nested map-of-lists round-trips")
    void deeplyNestedCollections_roundTrip() {
        final Config c = open();
        c.setValue("root.groups.g1", Arrays.asList(10, 20));
        c.setValue("root.groups.g2", Arrays.asList(30));
        c.setValue("root.meta.count", 2);
        c.save();

        final Config r = open();
        assertEquals(2, r.getList("root.groups.g1", Object.class).size());
        assertEquals(10, ((Number) r.getList("root.groups.g1", Object.class).get(0)).intValue());
        assertEquals(30, ((Number) r.getList("root.groups.g2", Object.class).get(0)).intValue());
        assertEquals(2, r.getInt("root.meta.count"));
    }

    @Test
    @Order(133)
    @DisplayName("[base] a very long string round-trips intact (no line splitting)")
    void veryLongString_roundTrip() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append("ab");
        }
        final String longValue = sb.toString(); // 10_000 chars on one logical line
        final Config c = open();
        c.setValue("long", longValue);
        c.save();

        final Config r = open();
        assertEquals(longValue, r.getString("long"));
    }

    @Test
    @Order(134)
    @DisplayName("[base] a string with tabs / quotes / backslashes round-trips")
    void specialChars_roundTrip() {
        final String value = "tab\tquote\"single'backslash\\brace}end";
        final Config c = open();
        c.setValue("special", value);
        c.save();

        final Config r = open();
        assertEquals(value, r.getString("special"));
    }

    @Test
    @Order(135)
    @DisplayName("[base] getKeysSections returns a section view per direct child")
    void getKeysSections_returnsChildSections() {
        final Config c = open();
        c.setValue("alpha.x", 1);
        c.setValue("beta.y", 2);

        final Set<String> sectionKeys = new LinkedHashSet<String>();
        for (final ConfigSection s : c.getKeysSections()) {
            sectionKeys.add(s.getSectionKey());
        }
        assertTrue(sectionKeys.contains("alpha"));
        assertTrue(sectionKeys.contains("beta"));
        assertEquals(2, sectionKeys.size());
    }

    @Test
    @Order(136)
    @DisplayName("[base] getList(path, type) is empty (never null) when the path is absent")
    void getListTyped_emptyWhenAbsent() {
        final Config c = open();
        c.setValue("present", Arrays.asList("a", "b"));

        assertEquals(2, c.getList("present", String.class).size());
        assertTrue(c.getList("missing", String.class).isEmpty());
    }

    @Test
    @Order(137)
    @DisplayName("[base] numeric widening/narrowing is coerced on bind")
    void numericWideningAndNarrowing_onBind() {
        final Config c = open();
        c.setValue("ratio", 2);   // int stored into a double field -> widen
        c.setValue("epoch", 7);   // int stored into a long field -> widen
        c.setValue("count", 9L);  // long (in int range) stored into an int field -> narrow

        final Dtos.PlainPojo p = c.loadAs(Dtos.PlainPojo.class, codec);
        assertEquals(2.0, p.ratio, 1e-9);
        assertEquals(7L, p.epoch);
        assertEquals(9, p.count);
    }

    @Test
    @Order(138)
    @DisplayName("[base] getLastModified / hasBeenModified track the durable file")
    void lifecycle_hasBeenModified_getLastModified() {
        final Config c = open();
        c.setValue("v", "a");
        c.save();
        assertTrue(c.getLastModified() > 0L);
        assertFalse(c.hasBeenModified()); // in sync right after a save

        final Config editor = open();
        editor.setValue("v", "a-much-longer-value-of-a-different-size"); // size differs -> fingerprint differs
        editor.save();
        assertTrue(c.hasBeenModified()); // the durable file changed under c
    }

    @Test
    @Order(139)
    @DisplayName("[base] a LoadIssueAware entity receives the collected issues")
    void loadIssueAware_receivesIssues() {
        final Config c = open();
        c.setValue("count", "NaN"); // a bad value -> a lenient LoadIssue
        final Dtos.IssueAwarePojo p = c.loadAs(Dtos.IssueAwarePojo.class, codec);
        assertNotNull(p.received);
        assertFalse(p.received.isEmpty());
    }

    @Test
    @Order(140)
    @DisplayName("[base] a throwing @PostLoad surfaces as a BindException")
    void postInjectThrows_surfacesBindException() {
        final Config c = open();
        c.setValue("port", 5);
        assertThrows(BindException.class, () -> c.loadAs(Dtos.PostLoadThrowsPojo.class, codec));
    }

    @Test
    @Order(141)
    @DisplayName("[base] an overridden @PostLoad runs once (de-duped by method name)")
    void inheritedPostLoad_deDupsByName() {
        final Config c = open();
        final Dtos.InheritedPostLoadSub p = c.loadAs(Dtos.InheritedPostLoadSub.class, codec);
        assertEquals(1, p.hookCalls); // not 2 (would be once per hierarchy level without de-dup)
    }

    @Test
    @Order(141)
    @DisplayName("[base] lifecycle @PreLoad/@PostLoad/@PreSave/@PostSave fire around read/write in order")
    void lifecycleHooks_fireAroundReadWriteInOrder() {
        final Config c = open();
        c.setValue("name", "file");

        final Dtos.LifecycleTrackedPojo read = c.bind(Dtos.LifecycleTrackedPojo.class, codec).read("");
        // preLoad ran before the bind (name=def), postLoad after (name=file)
        assertEquals(Arrays.asList("preLoad:def", "postLoad:file"), read.trace);

        final Dtos.LifecycleTrackedPojo write = new Dtos.LifecycleTrackedPojo();
        c.bind(Dtos.LifecycleTrackedPojo.class, codec).write("place", write);
        assertEquals(Arrays.asList("preSave", "postSave"), write.trace);
    }

    @Test
    @Order(141)
    @DisplayName("[base] ConfigLifecycle interface callbacks receive the bound section")
    void lifecycleInterface_callbacksReceiveTheBoundSection() {
        final Config c = open();
        c.setValue("sub.name", "file");

        final Dtos.LifecycleInterfacePojo read = c.bind(Dtos.LifecycleInterfacePojo.class, codec).read("sub");
        assertTrue(read.calls.contains("preLoad@sub"));
        assertTrue(read.calls.contains("postLoad@sub"));

        final Dtos.LifecycleInterfacePojo write = new Dtos.LifecycleInterfacePojo();
        c.bind(Dtos.LifecycleInterfacePojo.class, codec).write("sub", write);
        assertTrue(write.calls.contains("preSave@sub"));
        assertTrue(write.calls.contains("postSave@sub"));
    }

    @Test
    @Order(142)
    @DisplayName("[base] EntityBinder read / write / readInto")
    void entityBinder_read_write_readInto() {
        final Config c = open();

        // write: merge a POJO under a path (created if absent)
        final Dtos.PlainPojo w = new Dtos.PlainPojo();
        w.name = "w";
        w.count = 5;
        c.bind(Dtos.PlainPojo.class, codec).write("place", w);
        assertEquals("w", c.getString("place.name"));
        assertEquals(5, c.getInt("place.count"));

        // read: bind that subtree to a fresh instance
        final Dtos.PlainPojo at = c.bind(Dtos.PlainPojo.class, codec).read("place");
        assertEquals("w", at.name);
        assertEquals(5, at.count);

        // readInto: update an existing instance, keeping fields the tree does not carry
        c.getRoot().removeAll();
        c.setValue("count", 9);
        final Dtos.PlainPojo existing = new Dtos.PlainPojo();
        existing.name = "keep";
        existing.active = true;
        final Dtos.PlainPojo into = c.bind(Dtos.PlainPojo.class, codec).readInto("", existing);
        assertSame(existing, into);
        assertEquals(9, into.count);
        assertEquals("keep", into.name); // not overwritten (the tree had no name)
        assertTrue(into.active);
    }

    @Test
    @Order(143)
    @DisplayName("[base] binding yields fresh, value-equal instances detached from the tree and the source")
    void binding_freshDetachedInstances() {
        final Config c = open();
        final Dtos.PlainPojo existing = new Dtos.PlainPojo();
        existing.name = "keep";
        existing.active = true;

        c.setValue("a", existing);
        c.setValue("b", existing);
        c.save();

        // setValue stored equal data at both paths (two distinct nodes, equal by value)
        assertEquals(c.getValue("a"), c.getValue("b"));

        final Dtos.PlainPojo a = c.getValue("a", Dtos.PlainPojo.class);
        final Dtos.PlainPojo b = c.getValue("b", Dtos.PlainPojo.class);

        // value-equal content...
        assertEquals(a, b);
        // ...but every bind builds a NEW instance, never shared and never the source POJO
        assertTrue(a != b);
        assertTrue(a != existing);
        assertTrue(b != existing);

        // the bound instance is detached: mutating it does not touch the tree
        a.name = "mutated";
        assertEquals("keep", c.getString("a.name"));
    }

    @Test
    @Order(144)
    @DisplayName("[base] getList(path, type) binds typed elements; empty (never null) when absent or non-list")
    void getList_typedElements() {
        final Dtos.ListOfPojoPojo.Server s1 = new Dtos.ListOfPojoPojo.Server();
        s1.name = "alpha";
        s1.port = 1;
        final Dtos.ListOfPojoPojo.Server s2 = new Dtos.ListOfPojoPojo.Server();
        s2.name = "beta";
        s2.port = 2;

        final Config c = open();
        c.setValue("servers", Arrays.asList(s1, s2));
        c.save();

        final Config r = open();
        final List<Dtos.ListOfPojoPojo.Server> back =
                r.getList("servers", Dtos.ListOfPojoPojo.Server.class);
        assertEquals(2, back.size());
        assertEquals(s1, back.get(0));
        assertEquals(s2, back.get(1));

        // absent path -> empty list (never null)
        assertTrue(r.getList("missing", Dtos.ListOfPojoPojo.Server.class).isEmpty());
        // a scalar (non-list) -> empty list too
        r.setValue("scalar", 5);
        assertTrue(r.getList("scalar", Dtos.ListOfPojoPojo.Server.class).isEmpty());
    }

    @Test
    @Order(145)
    @DisplayName("[base] EntityBinder ConfigSection overloads + path-scoped readInto/readIntoResult")
    void entityBinder_sectionOverloadsAndReadInto() {
        final Config c = open();
        final ConfigSection place = c.getConfigSection("place");

        // write via the ConfigSection overload
        final Dtos.PlainPojo w = new Dtos.PlainPojo();
        w.name = "w";
        w.count = 5;
        c.bind(Dtos.PlainPojo.class, codec).write(place, w);
        assertEquals("w", c.getString("place.name"));
        assertEquals(5, c.getInt("place.count"));

        // read + readResult via the ConfigSection overload
        final Dtos.PlainPojo viaSection = c.bind(Dtos.PlainPojo.class, codec).read(place);
        assertEquals("w", viaSection.name);
        final BindResult<Dtos.PlainPojo> r = c.bind(Dtos.PlainPojo.class, codec).readResult(place);
        assertEquals("w", r.value().name);
        assertFalse(r.hasIssues());

        // path-scoped readInto keeps a field the subtree does not carry
        c.setValue("partial.count", 9);
        final Dtos.PlainPojo target = new Dtos.PlainPojo();
        target.name = "default";
        target.active = true;
        final Dtos.PlainPojo into = c.bind(Dtos.PlainPojo.class, codec).readInto("partial", target);
        assertSame(target, into);
        assertEquals(9, into.count);        // overwritten from the subtree
        assertEquals("default", into.name); // kept (the subtree had no 'name')
        assertTrue(into.active);            // kept

        // readIntoResult carries (value, issues)
        final BindResult<Dtos.PlainPojo> ir =
                c.bind(Dtos.PlainPojo.class, codec).readIntoResult("partial", new Dtos.PlainPojo());
        assertEquals(9, ir.value().count);
        assertFalse(ir.hasIssues());
    }

    @Test
    @Order(146)
    @DisplayName("[base] getOrSetValueIfAbsent seeds an absent POJO; getOrMergeValue seeds field-level")
    void getOrSetValueIfAbsent_pojoAndFieldLevel() {
        final Config c = open();
        c.setValue("db.count", 99); // a partial subtree: only 'count' present

        // field-level merge: the absent field 'name' is seeded from the default, the present 'count' is kept
        final Dtos.PlainPojo def = new Dtos.PlainPojo();
        def.name = "seeded";
        def.count = 1;
        final Dtos.PlainPojo result = c.getOrMergeValue("db", def);
        assertEquals(99, result.count);                 // the file wins for the present field
        assertEquals("seeded", result.name);            // the default supplied the missing field
        assertEquals("seeded", c.getString("db.name")); // ...and it was seeded into the tree
        assertEquals(99, c.getInt("db.count"));

        // the deprecated getOrSetValueIfAbsentInto alias delegates to the same field-level merge
        c.setValue("db2.count", 7);
        @SuppressWarnings("deprecation")
        final Dtos.PlainPojo viaAlias = c.getOrSetValueIfAbsentInto("db2", def);
        assertEquals(7, viaAlias.count);
        assertEquals("seeded", c.getString("db2.name"));

        // path-level: an absent path seeds the whole default and returns it
        final Dtos.PlainPojo def2 = new Dtos.PlainPojo();
        def2.name = "fresh";
        final Dtos.PlainPojo seeded = c.getOrSetValueIfAbsent("cfg", def2);
        assertSame(def2, seeded);
        assertEquals("fresh", c.getString("cfg.name"));
    }

    @Test
    @Order(147)
    @DisplayName("[base] getString on an object node returns the default, not raw JSON")
    void getString_onObject_returnsDefault() {
        final Config c = open();
        c.setValue("obj.x", 1);
        c.setValue("obj.y", 2);
        assertNull(c.getString("obj"));                      // an object is not a string -> null
        assertEquals("fallback", c.getString("obj", "fallback"));
        // the array-to-newlines affordance is unaffected
        c.setValue("lines", Arrays.asList("a", "b"));
        assertEquals("a\nb", c.getString("lines"));
    }

    @Test
    @Order(148)
    @DisplayName("[base] getUUID round-trips and tolerates malformed/absent without throwing")
    void getUUID_roundTripAndTolerant() {
        final Config c = open();
        final UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        c.setValue("id", id.toString());
        assertEquals(id, c.getUUID("id"));
        assertEquals(id, c.getUUID("id", UUID.randomUUID()));

        // a malformed value never throws: null on the no-arg overload, the default on the other
        final UUID fallback = UUID.fromString("00000000-0000-0000-0000-000000000001");
        c.setValue("bad", "not-a-uuid");
        assertNull(c.getUUID("bad"));
        assertEquals(fallback, c.getUUID("bad", fallback));

        // absent is symmetric with the numeric getters: null / the default
        assertNull(c.getUUID("missing"));
        assertEquals(fallback, c.getUUID("missing", fallback));
    }

    @Test
    @Order(149)
    @DisplayName("[base] hasNewSeededDefaults tracks default-seeding only, distinct from a plain setValue")
    void newDefaultValueToSave_tracksSeedingOnly() {
        final Config c = open();
        assertFalse(c.hasNewSeededDefaults());
        c.setValue("a", 1);              // an ordinary mutation does NOT flip the seed signal
        assertFalse(c.hasNewSeededDefaults());
        c.getOrSetValueIfAbsent("b", 2); // seeding an absent default flips it
        assertTrue(c.hasNewSeededDefaults());
        c.clearNewSeededDefaults();
        assertFalse(c.hasNewSeededDefaults());
        c.getOrSetValueIfAbsent("b", 999); // present now -> no seeding -> stays false
        assertFalse(c.hasNewSeededDefaults());
    }

    @Test
    @Order(150)
    @DisplayName("[base] migrateKey reports moved / already-migrated / source-absent / no-op outcomes")
    void migrateKey_reportsOutcome() {
        final Config c = open();
        c.setValue("old", "v");

        // a real move
        assertEquals(MigrationResult.MOVED, c.migrateKey("old", "new"));
        assertEquals("v", c.getString("new"));
        assertFalse(c.contains("old"));

        // re-run after the move: source gone, destination present -> benign
        assertEquals(MigrationResult.ALREADY_MIGRATED, c.migrateKey("old", "new"));

        // neither side exists -> suspicious (likely a typo); data stays unmigrated
        assertEquals(MigrationResult.SOURCE_ABSENT, c.migrateKey("ghost", "elsewhere"));

        // same-path / root no-ops carry their own outcome
        c.setValue("x", 1);
        assertEquals(MigrationResult.SAME_PATH, c.migrateKey("x", "x"));
        assertEquals(MigrationResult.INVALID_ROOT, c.migrateKey("", "y"));

        // collision: both exist -> the source overwrites the destination, reported as MOVED
        c.setValue("src", "fromSrc");
        c.setValue("dst", "old-dst");
        assertEquals(MigrationResult.MOVED, c.migrateKey("src", "dst"));
        assertEquals("fromSrc", c.getString("dst"));
        assertFalse(c.contains("src"));
    }

    @Test
    @Order(151)
    @DisplayName("[base] a key containing a dot is addressed with \\. and round-trips through the codec")
    void dottedKey_escaped_roundTrips() {
        final Config c = open();
        c.setValue("rates.usd\\.brl", 5);   // ONE key "usd.brl" under "rates"
        c.setValue("rates.eur", 2);
        c.setValue("top\\.level", "x");     // a dotted key at the root
        // a literal-dot key and a nested path of the same spelling stay distinct
        c.setValue("a\\.b", 1);             // literal key "a.b"
        c.setValue("a.b", 9);               // nested a -> b

        assertEquals(5, c.getInt("rates.usd\\.brl"));
        assertEquals(2, c.getInt("rates.eur"));
        assertEquals("x", c.getString("top\\.level"));
        assertEquals(1, c.getInt("a\\.b"));
        assertEquals(9, c.getInt("a.b"));
        assertTrue(c.getKeys("rates").contains("usd.brl"));        // shallow keys are literal child names
        assertTrue(c.getKeys("", true).contains("rates.usd\\.brl")); // deep keys are escaped path keys

        c.save();
        final Config r = open();
        assertEquals(5, r.getInt("rates.usd\\.brl"));
        assertEquals(2, r.getInt("rates.eur"));
        assertEquals("x", r.getString("top\\.level"));
        assertEquals(1, r.getInt("a\\.b"));
        assertEquals(9, r.getInt("a.b"));
    }

    @Test
    @Order(152)
    @DisplayName("[base] a comment on a dotted key (\\.) round-trips")
    void dottedKey_comment_roundTrips() {
        assumeComments();
        final Config c = open();
        c.setValue("rates.usd\\.brl", 5);
        c.setComment("rates.usd\\.brl", "the USD per BRL rate");
        assertEquals("the USD per BRL rate", c.getComment("rates.usd\\.brl")); // attached in memory
        c.save();

        final Config r = open();
        assertEquals(5, r.getInt("rates.usd\\.brl"));
        if (supportsLosslessComments()) {
            assertEquals("the USD per BRL rate", r.getComment("rates.usd\\.brl"));
        }
    }

    @Test
    @Order(153)
    @DisplayName("[base] Config.inMemory binds POJOs without a file but cannot be saved")
    void inMemory_bindsPojosButCannotSave() {
        final Config c = Config.inMemory();
        final Dtos.PlainPojo p = new Dtos.PlainPojo();
        p.name = "x";
        p.count = 7;
        c.setValue("cfg", p);                              // annotation-aware write works in memory
        assertEquals("x", c.getString("cfg.name"));
        final Dtos.PlainPojo back = c.getValue("cfg", Dtos.PlainPojo.class);
        assertEquals(7, back.count);
        assertThrows(IllegalStateException.class, c::save); // no file -> cannot persist
    }

    @Test
    @Order(154)
    @DisplayName("[base] save(codec) writes another format one-shot, leaving the live codec unchanged")
    void saveWithCodec_oneShotOtherFormat() {
        final Config c = open();
        c.setValue("server.host", "localhost");
        c.setValue("server.port", 25565);
        c.save(new JsonCodec()); // dump as JSON into the same backing file
        final Config asJson = track(Config.open(file(), new JsonCodec()));
        assertEquals("localhost", asJson.getString("server.host"));
        assertEquals(25565, asJson.getInt("server.port"));
    }

    @Test
    @Order(155)
    @DisplayName("[base] changeCodec switches the format used for the next save")
    void changeCodec_switchesFormat() {
        final Config c = open();
        c.setValue("k", "v");
        c.changeCodec(new JsonCodec());
        c.save(); // the swapped codec now controls the output format
        final Config asJson = track(Config.open(file(), new JsonCodec()));
        assertEquals("v", asJson.getString("k"));
    }

    @Test
    @Order(156)
    @DisplayName("[base] getKeys(deep) returns every descendant path; a sub-path query is relative")
    void getKeys_deep_multiLevel() {
        final Config c = open();
        c.setValue("a.b.c", 1);
        c.setValue("a.b.d", 2);
        c.setValue("a.e", 3);
        c.setValue("f", 4);

        assertEquals(new LinkedHashSet<>(Arrays.asList("a", "f")), c.getKeys());        // shallow: direct children
        assertEquals(new LinkedHashSet<>(Arrays.asList("b", "e")), c.getKeys("a"));

        final Set<String> deep = c.getKeys("", true);
        assertTrue(deep.containsAll(Arrays.asList("a", "a.b", "a.b.c", "a.b.d", "a.e", "f")));

        final Set<String> deepA = c.getKeys("a", true); // relative to "a", not prefixed with it
        assertTrue(deepA.containsAll(Arrays.asList("b", "b.c", "b.d", "e")));
        assertFalse(deepA.contains("a.b"));
    }

    @Test
    @Order(157)
    @DisplayName("[base] getOrSetValueIfAbsent(path, List) seeds when absent and reads back typed when present")
    void getOrSetValueIfAbsent_listOverload() {
        final Config c = open();
        // absent -> seeds the default list and returns it
        final List<String> seeded = c.getOrSetValueIfAbsent("tags", Arrays.asList("a", "b", "c"));
        assertEquals(Arrays.asList("a", "b", "c"), seeded);
        assertEquals(Arrays.asList("a", "b", "c"), c.getStringList("tags"));
        // present -> the file wins, read back to the default's element type
        assertEquals(Arrays.asList("a", "b", "c"), c.getOrSetValueIfAbsent("tags", Arrays.asList("x")));

        // a list of POJOs reads back typed
        final Dtos.ListOfPojoPojo.Server s1 = new Dtos.ListOfPojoPojo.Server();
        s1.name = "s1";
        s1.port = 1;
        final Dtos.ListOfPojoPojo.Server s2 = new Dtos.ListOfPojoPojo.Server();
        s2.name = "s2";
        s2.port = 2;
        c.getOrSetValueIfAbsent("servers", Arrays.asList(s1, s2));
        final Dtos.ListOfPojoPojo.Server ignored = new Dtos.ListOfPojoPojo.Server();
        ignored.name = "ignored";
        ignored.port = 0;
        final List<Dtos.ListOfPojoPojo.Server> back =
                c.getOrSetValueIfAbsent("servers", Arrays.asList(ignored));
        assertEquals(2, back.size());
        assertEquals("s1", back.get(0).name);
        assertEquals(2, back.get(1).port);
    }

    @Test
    @Order(158)
    @DisplayName("[base] setValueIfAbsent seeds only when absent; an existing value and its comment win")
    void setValueIfAbsent_seedsOnlyWhenAbsent() {
        final Config c = open();
        c.setValueIfAbsent("port", 8080);
        assertEquals(8080, c.getInt("port"));
        c.setValueIfAbsent("port", 9999); // present -> ignored
        assertEquals(8080, c.getInt("port"));

        // the comment overload seeds the value and a default comment only where absent (comments are
        // in-memory regardless of the codec's file fidelity)
        c.setValueIfAbsent("host", "localhost", "the bind host");
        assertEquals("localhost", c.getString("host"));
        assertEquals("the bind host", c.getComment("host"));
        c.setValueIfAbsent("host", "other", "ignored"); // value present, comment present -> unchanged
        assertEquals("localhost", c.getString("host"));
        assertEquals("the bind host", c.getComment("host"));
    }

    // ============================================================================
    //  The kitchen sink: one DTO touching every type family + a 5-level nesting
    // ============================================================================

    @Test
    @Order(160)
    @DisplayName("[base] the ultra-complex DTO round-trips across every type family and a 5-level nesting")
    void ultraComplexDto_roundTrip() {
        final UltraComplexDTO src = new UltraComplexDTO();
        // Non-finite doubles (NaN / ±Infinity) are PARKED until Jackson can write them as valid TOML. Its
        // TOML writer currently emits the bare Java literals (NaN/Infinity), which are not valid TOML and
        // fail to re-parse, corrupting the whole document. Until that is fixed upstream they would not
        // round-trip consistently across the codec matrix, so they are cleared on every codec here and left
        // unasserted. Re-enable (and assert them) once the upstream writer emits nan/inf/-inf.
        src.nanValue = null;
        src.positiveInfinity = null;
        src.negativeInfinity = null;

        final Config c = open();
        c.setValue("cfg", src); // annotation-aware override: seeds @Comment, relocates @Key names
        c.save();

        final Config r = open();
        // Guard the whole round-trip: the emitted document must parse cleanly. Without this, a corrupt save
        // would back up to .bak and start empty, and every typed read below would silently pass against the
        // DTO's own field initializers instead of round-tripped data.
        assertEquals(LoadStatus.OK, r.lastLoadStatus());
        final UltraComplexDTO back = r.getValue("cfg", UltraComplexDTO.class);
        assertNotNull(back);

        // ---- strings: escaping, emptiness, multi-line, Unicode, codec-sensitive ----
        assertEquals(src.string, back.string);
        assertEquals("", back.emptyString);
        assertEquals("   ", back.blankString);
        assertNull(back.nullString);
        assertEquals(src.multilineString, back.multilineString);
        assertEquals(src.unicodeString, back.unicodeString);
        assertEquals(src.specialChars, back.specialChars);
        assertEquals(src.quotedString, back.quotedString);
        assertEquals(src.yamlSensitive, back.yamlSensitive);
        assertEquals(src.emoji, back.emoji);
        assertEquals(src.veryLongString, back.veryLongString);

        // ---- primitives + integer boundaries ----
        assertTrue(back.bool);
        assertEquals(Byte.MAX_VALUE, back.byteValue);
        assertEquals(Short.MAX_VALUE, back.shortValue);
        assertEquals(Integer.MAX_VALUE, back.intValue);
        assertEquals(Long.MAX_VALUE, back.longValue);
        assertEquals(Float.MAX_VALUE, back.floatValue, 0.0f);
        assertEquals(Double.MAX_VALUE, back.doubleValue, 0.0);
        assertEquals('A', back.charValue);
        assertEquals(Integer.MIN_VALUE, back.minInt);
        assertEquals(Long.MIN_VALUE, back.minLong);
        assertEquals(0, back.zero);
        assertEquals(-100, back.negative);

        // ---- wrapper types (one deliberately null) ----
        assertNull(back.wrapperBool);
        assertEquals(Integer.valueOf(123), back.wrapperInt);
        assertEquals(Double.valueOf(123.45), back.wrapperDouble);
        assertEquals(Character.valueOf('Ω'), back.wrapperChar);

        // ---- big numbers ----
        assertEquals(src.bigInteger, back.bigInteger); // exact on every codec
        // BigDecimal scale survives only on TOML; JSON-family parsers read a float as a double, collapsing
        // the scale, so compare the magnitude rather than the exact unscaled value.
        assertEquals(src.bigDecimal.doubleValue(), back.bigDecimal.doubleValue(),
                Math.abs(src.bigDecimal.doubleValue()) * 1e-9);

        // The non-finite doubles were parked above (cleared before the save), so nothing to assert here.
        // -0.0 is finite and unaffected: it round-trips everywhere (0.0 on TOML, sign lost), within a zero delta.
        assertEquals(0.0, back.negativeZero, 0.0);

        // ---- date / time (ISO-8601); offset/zoned compared by instant to ignore zone representation ----
        assertEquals(src.localDate, back.localDate);
        assertEquals(src.localTime, back.localTime);
        assertEquals(src.localDateTime, back.localDateTime);
        assertEquals(src.instant, back.instant);
        assertEquals(src.duration, back.duration);
        assertEquals(src.period, back.period);
        assertEquals(src.offsetDateTime.toInstant(), back.offsetDateTime.toInstant());
        assertEquals(src.zonedDateTime.toInstant(), back.zonedDateTime.toInstant());

        // ---- arrays ----
        assertArrayEquals(src.stringArray, back.stringArray);
        assertArrayEquals(src.intArray, back.intArray);
        assertArrayEquals(src.booleanArray, back.booleanArray);
        assertEquals(0, back.emptyArray.length);

        // ---- collections (compared representation-agnostically via a list copy) ----
        assertEquals(src.stringList, back.stringList);
        assertEquals(src.integerList, back.integerList);
        assertEquals(src.doubleList, back.doubleList);
        assertEquals(src.nestedList, back.nestedList);
        assertEquals(new ArrayList<String>(src.stringSet), new ArrayList<String>(back.stringSet));
        assertEquals(Arrays.asList("A", "B", "C"), new ArrayList<String>(back.sortedSet)); // TreeSet order
        assertEquals(new ArrayList<String>(src.queue), new ArrayList<String>(back.queue));
        assertEquals(new ArrayList<Integer>(src.deque), new ArrayList<Integer>(back.deque));
        assertTrue(back.emptyList.isEmpty());
        assertEquals(src.immutableList, back.immutableList);

        // ---- maps (insertion order preserved; ORDER_MAP_ENTRIES_BY_KEYS is off) ----
        assertEquals(src.stringMap, back.stringMap);
        assertEquals(src.integerMap, back.integerMap);
        assertEquals("root", back.nestedMap.get("label"));
        assertEquals(Boolean.TRUE, back.nestedMap.get("flag"));
        assertEquals(7, ((Number) back.nestedMap.get("count")).intValue());
        assertEquals(src.deepMap, back.deepMap);
        assertTrue(back.emptyMap.isEmpty());

        // ---- enums ----
        assertEquals(UltraComplexDTO.UltraEnum.SECOND, back.enumValue);
        assertEquals(src.enumList, back.enumList);

        // ---- optional (present / empty / primitive specialization) ----
        assertEquals(Optional.of("value"), back.optionalString);
        assertFalse(back.emptyOptional.isPresent());
        assertTrue(back.optionalInt.isPresent());
        assertEquals(42, back.optionalInt.getAsInt());

        // ---- reflective types generic binders often miss ----
        assertEquals("generic", back.object);
        assertNull(back.nullObject);
        assertEquals(String.class, back.classType);
        assertEquals(src.uuid, back.uuid);
        assertEquals(src.uri, back.uri);
        assertEquals(src.locale, back.locale);
        assertEquals(src.currency, back.currency);
        assertEquals(src.zoneId, back.zoneId);

        // ---- nested objects (single + list of) ----
        assertEquals(src.nestedObject, back.nestedObject);
        assertEquals(src.nestedObjectList, back.nestedObjectList);

        // ---- five-level deep nesting: whole-chain value equality, the leaf via the typed view, and the
        // same leaf via the dynamic path (each level renamed by @Key) ----
        assertEquals(src.deepNesting, back.deepNesting);
        assertEquals(5, back.deepNesting.level2.level3.level4.level5.value);
        assertEquals(UltraComplexDTO.UltraEnum.THIRD, back.deepNesting.level2.level3.level4.level5.mode);
        assertEquals(5, r.getInt("cfg.deep-nesting.level-2.level-3.level-4.level-5.value"));
        assertEquals("level-5", r.getString("cfg.deep-nesting.level-2.level-3.level-4.level-5.name"));

        // ---- the dynamic path API reads the same data as the typed view ----
        assertEquals("Hello World", r.getString("cfg.string"));
        assertEquals(Long.MAX_VALUE, r.getLong("cfg.max-long"));
        assertEquals(Integer.MIN_VALUE, r.getInt("cfg.min-int"));
        assertEquals(Arrays.asList("A", "B", "C"), r.getStringList("cfg.string-list"));

        // ---- a @Comment seeded through the kitchen-sink write survives where the codec keeps comments ----
        if (supportsComments()) {
            assertEquals("A plain string with no characters needing special handling", r.getComment("cfg.string"));
        }
    }

    // ============================================================================
    //  setValue = override (default) vs mergeValue = merge  (+ getOrMergeValue)
    // ============================================================================

    @Test
    @Order(161)
    @DisplayName("[base] setValue(pojo) override: a partial POJO over a full one drops the extra keys")
    void setValuePojo_overrideDropsExtraKeys() {
        final Config c = open();
        final Dtos.PlainPojo plain = new Dtos.PlainPojo();
        plain.name = "keep";
        plain.active = true;
        plain.ratio = 1f;

        final Dtos.PlainPojoPartial partial = new Dtos.PlainPojoPartial();
        partial.name = "unkeep";
        partial.active = false;

        c.setValue("plain", plain);               // a full POJO: 5 keys
        c.setValue("plainAfterPartial", plain);
        c.setValue("plainAfterPartial", partial); // override -> only the partial's 2 keys remain

        assertEquals(5, c.getKeys("plain").size());               // the sibling is untouched
        assertTrue(c.contains("plain.count"));
        assertEquals(2, c.getKeys("plainAfterPartial").size());   // override dropped count/ratio/epoch
        assertTrue(c.getKeys("plainAfterPartial").containsAll(Arrays.asList("name", "active")));
        assertFalse(c.contains("plainAfterPartial.count"));
        assertFalse(c.contains("plainAfterPartial.ratio"));
        assertFalse(c.contains("plainAfterPartial.epoch"));
        assertEquals("unkeep", c.getString("plainAfterPartial.name"));

        c.save();
        final Config r = open();
        assertEquals(2, r.getKeys("plainAfterPartial").size());   // the shape survives the round-trip
        assertFalse(r.contains("plainAfterPartial.count"));
    }

    @Test
    @Order(162)
    @DisplayName("[base] mergeValue(pojo): a partial POJO over a full one keeps the extra keys")
    void mergeValuePojo_keepsExtraKeys() {
        final Config c = open();
        final Dtos.PlainPojo plain = new Dtos.PlainPojo();
        plain.name = "keep";
        plain.active = true;
        plain.ratio = 1f;

        final Dtos.PlainPojoPartial partial = new Dtos.PlainPojoPartial();
        partial.name = "unkeep";
        partial.active = false;

        c.mergeValue("merged", plain);
        c.mergeValue("merged", partial); // merge -> the 5 keys stay; name/active come from the partial

        assertEquals(5, c.getKeys("merged").size());
        assertEquals("unkeep", c.getString("merged.name")); // the partial won for the keys it declares
        assertFalse(c.getBoolean("merged.active"));
        assertTrue(c.contains("merged.count"));             // the full POJO's extra keys survive the merge
        assertTrue(c.contains("merged.ratio"));
        assertTrue(c.contains("merged.epoch"));
    }

    @Test
    @Order(163)
    @DisplayName("[base] ConfigSection.mergeValue / getOrMergeValue delegate with the sub-path prefixed")
    void configSection_mergeValueAndGetOrMerge() {
        final Config c = open();
        final ConfigSection sec = c.getConfigSection("root");

        final Dtos.PlainPojo plain = new Dtos.PlainPojo();
        plain.name = "a";
        plain.count = 5;
        sec.setValue("node", plain); // 5 keys under root.node

        final Dtos.PlainPojoPartial partial = new Dtos.PlainPojoPartial();
        partial.name = "b";
        sec.mergeValue("node", partial); // merge keeps the extra keys
        assertEquals("b", c.getString("root.node.name"));
        assertTrue(c.contains("root.node.count"));

        // getOrMergeValue seeds a missing field into a partial subtree (the file wins on present fields)
        c.setValue("root.cfg.count", 42);
        final Dtos.PlainPojo def = new Dtos.PlainPojo();
        def.name = "seed";
        def.count = 1;
        final Dtos.PlainPojo out = sec.getOrMergeValue("cfg", def);
        assertEquals(42, out.count);
        assertEquals("seed", c.getString("root.cfg.name"));
    }

    @Test
    @Order(164)
    @DisplayName("[base] in-memory setValue overrides and mergeValue merges without a file")
    void inMemory_overrideAndMerge() {
        final Config c = Config.inMemory();

        final Dtos.PlainPojo plain = new Dtos.PlainPojo();
        plain.name = "keep";
        plain.count = 9;

        final Dtos.PlainPojoPartial partial = new Dtos.PlainPojoPartial();
        partial.name = "x";

        c.setValue("o", plain);
        c.setValue("o", partial); // override -> the extra keys are gone
        assertFalse(c.contains("o.count"));
        assertEquals("x", c.getString("o.name"));

        c.setValue("m", plain);
        c.mergeValue("m", partial); // merge -> the extra keys stay
        assertTrue(c.contains("m.count"));
        assertEquals("x", c.getString("m.name"));
    }

    @Test
    @Order(165)
    @DisplayName("[base] setValue(pojo) override drops a stale comment and re-seeds @Comment (SET_IF_ABSENT included)")
    void setValuePojo_overrideDropsStaleCommentsAndReseeds() {
        assumeComments();
        final Config c = open();
        c.setComment("retries", "USER WROTE THIS"); // retries' @Comment is SET_IF_ABSENT
        c.setComment("obsolete", "orphan doc");     // a key CommentedPojo does not declare

        c.setValue("", new Dtos.CommentedPojo());   // OVERRIDE at the root clears the subtree's comments first

        assertNull(c.getComment("obsolete"));                         // the orphan comment is dropped
        assertEquals("Tune this if needed", c.getComment("retries")); // SET_IF_ABSENT re-seeds after the clear
        assertTrue(c.getCommentTree().getHeader().contains("Database settings")); // class @Comment seeds the header
    }

    // ============================================================================
    //  Self-describing types (Jackson-native @JsonValue / @JsonCreator, zero registration)
    // ============================================================================

    @Test
    @Order(200)
    @DisplayName("[base] a @JsonValue scalar self-describes: stored as a string solo, as a field, and one-per-line in a list")
    void selfDescribing_jsonValueScalar_soloFieldList() {
        final Config c = open();
        c.setValue("coord", new Dtos.SelfDescribingScalar(3, 4)); // solo value

        final Dtos.SelfDescribingHolder h = new Dtos.SelfDescribingHolder();
        h.coord = new Dtos.SelfDescribingScalar(1, 2);
        h.size = new Dtos.SelfDescribingObject(16, 9);
        h.path = Arrays.asList(new Dtos.SelfDescribingScalar(5, 6), new Dtos.SelfDescribingScalar(7, 8));
        c.setValue("holder", h);
        c.save();

        final Config r = open();
        // solo: stored AS A STRING (the proof @JsonValue was honored) and rebindable via @JsonCreator
        assertTrue(r.getNode("coord").isTextual(),
                "expected a @JsonValue scalar stored as text, got " + r.getNode("coord"));
        assertEquals("3:4", r.getString("coord"));
        assertEquals(new Dtos.SelfDescribingScalar(3, 4), r.getValue("coord", Dtos.SelfDescribingScalar.class));

        // as a POJO field
        assertEquals("1:2", r.getString("holder.coord"));
        // inside a list -> a string-list, one compact line per element
        assertEquals(Arrays.asList("5:6", "7:8"), r.getStringList("holder.path"));
    }

    @Test
    @Order(201)
    @DisplayName("[base] a @JsonCreator immutable object self-describes and rebuilds from its properties")
    void selfDescribing_jsonCreatorObject_roundTrip() {
        final Config c = open();
        final Dtos.SelfDescribingHolder h = new Dtos.SelfDescribingHolder();
        h.coord = new Dtos.SelfDescribingScalar(1, 2);
        h.size = new Dtos.SelfDescribingObject(16, 9);
        h.path = Arrays.asList(new Dtos.SelfDescribingScalar(5, 6));
        c.setValue("holder", h);
        c.save();

        final Config r = open();
        // the immutable object serialized to a plain object (no @JsonValue)
        assertEquals(16, r.getInt("holder.size.width"));
        assertEquals(9, r.getInt("holder.size.height"));

        // full typed read-back reconstructs every context (scalar solo-field, object, list) with no registration
        final Dtos.SelfDescribingHolder back = r.getValue("holder", Dtos.SelfDescribingHolder.class);
        assertEquals(h, back);
    }

    @Test
    @Order(202)
    @DisplayName("[base] a @JsonValue enum serializes by its custom form (not name()) on both the dynamic and field paths")
    void selfDescribing_jsonValueEnum_customFormWinsOverName() {
        final Config c = open();
        c.setValue("solo", Dtos.CodeEnum.ALPHA);           // dynamic path (NodeCoercion)
        final Dtos.JsonValueEnumHolder h = new Dtos.JsonValueEnumHolder();
        h.mode = Dtos.CodeEnum.BETA;
        c.setValue("holder", h);                           // mapper path (POJO field)
        c.save();

        final Config r = open();
        assertEquals("a", r.getString("solo"));            // @JsonValue "a" wins over the name "ALPHA"
        assertEquals("b", r.getString("holder.mode"));     // ...and on the field path too
        assertEquals(Dtos.CodeEnum.ALPHA, r.getValue("solo", Dtos.CodeEnum.class));
        assertEquals(Dtos.CodeEnum.BETA, r.getValue("holder", Dtos.JsonValueEnumHolder.class).mode);
    }

    // ============================================================================
    //  Distinct collection-element form (rich solo, compact in a list), resolved per-codec
    // ============================================================================

    @Test
    @Order(210)
    @DisplayName("[base] a type is rich solo but compact in a list: setValue keeps the object, a list auto-writes strings")
    void elementForm_richSolo_compactInList_bothFormsRoundTrip() {
        final Config c = open();
        c.setValue("home", new Dtos.DualFormPos(1, 2, 3)); // solo -> rich object
        c.setValue("spots", Arrays.asList(                 // list -> compact strings (auto, like @KeyIndex)
                new Dtos.DualFormPos(4, 5, 6), new Dtos.DualFormPos(7, 8, 9)));
        c.save();

        final Config r = open();
        // solo stayed rich
        assertTrue(r.getNode("home").isObject(), "expected a rich object solo, got " + r.getNode("home"));
        assertEquals(1, r.getInt("home.x"));
        assertEquals(new Dtos.DualFormPos(1, 2, 3), r.getValue("home", Dtos.DualFormPos.class));
        // the list is compact, one string per element
        assertEquals(Arrays.asList("4 5 6", "7 8 9"), r.getStringList("spots"));
        // and getList reads it straight back
        assertEquals(Arrays.asList(new Dtos.DualFormPos(4, 5, 6), new Dtos.DualFormPos(7, 8, 9)),
                r.getList("spots", Dtos.DualFormPos.class));
    }

    @Test
    @Order(211)
    @DisplayName("[base] getList of an element-form type is tolerant: it reads a compact string AND a rich object element")
    void elementForm_getList_tolerantOfBothShapes() {
        final Map<String, Object> rich = new LinkedHashMap<>();
        rich.put("x", 1);
        rich.put("y", 2);
        rich.put("z", 3);

        final Config c = open();
        c.setValue("mix", Arrays.asList("4 5 6", rich)); // one compact string, one rich object, in one array

        final List<Dtos.DualFormPos> back = c.getList("mix", Dtos.DualFormPos.class);
        assertEquals(2, back.size());
        assertEquals(new Dtos.DualFormPos(4, 5, 6), back.get(0)); // string -> fromElementString
        assertEquals(new Dtos.DualFormPos(1, 2, 3), back.get(1)); // object -> the rich bind
    }

    @Test
    @Order(212)
    @DisplayName("[base] getList of an element-form type skips a malformed compact element leniently")
    void elementForm_getList_skipsBadElementLeniently() {
        final Config c = open();
        c.setValue("xs", Arrays.asList("4 5 6", "not-a-pos"));

        final List<Dtos.DualFormPos> back = c.getList("xs", Dtos.DualFormPos.class);
        assertEquals(1, back.size()); // the good element survives; the malformed one is skipped, no throw
        assertEquals(new Dtos.DualFormPos(4, 5, 6), back.get(0));
    }

    @Test
    @Order(220)
    @DisplayName("[order] pinLast keeps a key at the bottom, even after later-seeded keys and reload")
    void pinLast_sinksKeyBelowLaterSeededKeys() throws IOException {
        assumeKeyOrdering();
        final Config c = open();
        c.setValue("alpha", 1);
        c.setValue("debug", true);
        c.setValue("beta", 2);
        c.pinLast("debug");
        c.setValue("gamma", 3); // seeded AFTER the pin, in insertion order
        c.setValue("delta", 4);
        c.save();

        final String text = readText();
        assertTrue(text.indexOf("debug") > text.indexOf("gamma"), text);
        assertTrue(text.indexOf("debug") > text.indexOf("delta"), text);

        // Re-assert the pin on a fresh handle (as a startup would), seed more -> it stays last.
        final Config r = open();
        r.pinLast("debug");
        r.setValue("epsilon", 5);
        r.save();
        final String text2 = readText();
        assertTrue(text2.indexOf("debug") > text2.indexOf("epsilon"), text2);
    }

    @Test
    @Order(221)
    @DisplayName("[order] pinFirst floats a key above its siblings")
    void pinFirst_floatsKeyToTop() throws IOException {
        assumeKeyOrdering();
        final Config c = open();
        c.setValue("one", 1);
        c.setValue("two", 2);
        c.setValue("moved", 3);
        c.pinFirst("moved");
        c.save();

        final String text = readText();
        assertTrue(text.indexOf("moved") < text.indexOf("one"), text);
        assertTrue(text.indexOf("moved") < text.indexOf("two"), text);
    }

    @Test
    @Order(222)
    @DisplayName("[order] unpin restores the captured/append order")
    void unpin_restoresAppendOrder() throws IOException {
        assumeKeyOrdering();
        final Config c = open();
        c.setValue("first", 1);
        c.setValue("sink", 2);
        c.setValue("third", 3);
        c.pinLast("sink");
        c.unpin("sink"); // no longer forced last
        c.save();

        final String text = readText();
        assertTrue(text.indexOf("sink") < text.indexOf("third"), text); // back in insertion order
    }

    @Test
    @Order(223)
    @DisplayName("[order] a pin acts within the key's own level only")
    void pin_isScopedToOwnLevel() throws IOException {
        assumeKeyOrdering();
        final Config c = open();
        c.setValue("srv.aaa", 1);
        c.setValue("srv.zzz", 2);
        c.pinFirst("srv.zzz"); // float zzz above aaa WITHIN srv
        c.setValue("top", 9);
        c.save();

        final String text = readText();
        assertTrue(text.indexOf("zzz") < text.indexOf("aaa"), text);
    }

    // ===================== nested lifecycle composition (Order 230+) =====================

    @Test
    @Order(230)
    @DisplayName("[lifecycle] a top-level List<T> fires each element at list[i] and round-trips its manual extra")
    void nestedLifecycle_topLevelList_composesAtIndexPaths() throws IOException {
        final Config c = open();
        final Dtos.HookedPojo a = new Dtos.HookedPojo("a", 1, "extraA");
        final Dtos.HookedPojo b = new Dtos.HookedPojo("b", 2, "extraB");
        c.setValue("snapshots", Arrays.asList(a, b));

        // The elements I passed in are the ones whose save hooks fired, at their real indexed sub-paths.
        assertTrue(a.fires.contains("preSave@snapshots[0]"), a.fires.toString());
        assertTrue(a.fires.contains("postSave@snapshots[0]"), a.fires.toString());
        assertTrue(b.fires.contains("postSave@snapshots[1]"), b.fires.toString());
        c.save();

        final Config r = open();
        final List<Dtos.HookedPojo> read = r.getList("snapshots", Dtos.HookedPojo.class);
        assertEquals(2, read.size());
        // The manual `extra` a top-level postSave wrote survived, and postLoad reconstructed it.
        assertEquals("extraA", read.get(0).extra);
        assertEquals("extraB", read.get(1).extra);
        assertTrue(read.get(0).fires.contains("postLoad@snapshots[0]"), read.get(0).fires.toString());
        assertTrue(read.get(1).fires.contains("postLoad@snapshots[1]"), read.get(1).fires.toString());
        // PRE_LOAD never fires in nested position (a list element has no top-level bind of its own).
        for (final Dtos.HookedPojo h : read) {
            assertFalse(h.fires.toString().contains("preLoad@"), h.fires.toString());
        }
    }

    @Test
    @Order(231)
    @DisplayName("[lifecycle] a top-level @KeyIndex collection fires each element at base.<id> and round-trips its extra")
    void nestedLifecycle_keyIndexList_composesAtIdPaths() throws IOException {
        final Config c = open();
        final Dtos.HookedKeyedPojo x = new Dtos.HookedKeyedPojo("alpha", 10, "eA");
        final Dtos.HookedKeyedPojo y = new Dtos.HookedKeyedPojo("beta", 20, "eB");
        c.setValue("accounts", Arrays.asList(x, y));
        assertTrue(x.fires.contains("postSave@accounts.alpha"), x.fires.toString());
        assertTrue(y.fires.contains("postSave@accounts.beta"), y.fires.toString());
        c.save();

        final Config r = open();
        final List<Dtos.HookedKeyedPojo> read = r.getList("accounts", Dtos.HookedKeyedPojo.class);
        assertEquals(2, read.size());
        final Map<String, Dtos.HookedKeyedPojo> byId = new LinkedHashMap<String, Dtos.HookedKeyedPojo>();
        for (final Dtos.HookedKeyedPojo h : read) {
            byId.put(h.id, h);
        }
        assertEquals("eA", byId.get("alpha").extra);
        assertEquals("eB", byId.get("beta").extra);
        assertTrue(byId.get("alpha").fires.contains("postLoad@accounts.alpha"), byId.get("alpha").fires.toString());
    }

    @Test
    @Order(232)
    @DisplayName("[lifecycle] nested field / Map value / List element all fire at exact sub-paths; PRE_LOAD stays top-level")
    void nestedLifecycle_withinEntity_composesEverywhere() throws IOException {
        final Config c = open();
        final Dtos.HookedOwnerPojo owner = new Dtos.HookedOwnerPojo();
        owner.child = new Dtos.HookedPojo("c", 1, "childExtra");
        owner.byName.put("k", new Dtos.HookedPojo("m", 2, "mapExtra"));
        owner.items.add(new Dtos.HookedPojo("i", 3, "itemExtra"));
        c.setValue("owner", owner);

        assertTrue(owner.child.fires.contains("postSave@owner.child"), owner.child.fires.toString());
        assertTrue(owner.byName.get("k").fires.contains("postSave@owner.byName.k"), owner.byName.get("k").fires.toString());
        assertTrue(owner.items.get(0).fires.contains("postSave@owner.items[0]"), owner.items.get(0).fires.toString());
        c.save();

        final Config r = open();
        final Dtos.HookedOwnerPojo read = r.getValue("owner", Dtos.HookedOwnerPojo.class);
        assertEquals("childExtra", read.child.extra);
        assertEquals("mapExtra", read.byName.get("k").extra);
        assertEquals("itemExtra", read.items.get(0).extra);
        assertTrue(read.child.fires.contains("postLoad@owner.child"), read.child.fires.toString());
        assertTrue(read.byName.get("k").fires.contains("postLoad@owner.byName.k"), read.byName.get("k").fires.toString());
        assertTrue(read.items.get(0).fires.contains("postLoad@owner.items[0]"), read.items.get(0).fires.toString());

        // PRE_LOAD fired for the top-level owner only, never for the nested entities.
        assertTrue(read.fires.contains("preLoad@owner"), read.fires.toString());
        assertFalse(read.child.fires.toString().contains("preLoad@"), read.child.fires.toString());
        assertFalse(read.byName.get("k").fires.toString().contains("preLoad@"), read.byName.get("k").fires.toString());
        assertFalse(read.items.get(0).fires.toString().contains("preLoad@"), read.items.get(0).fires.toString());
    }

    @Test
    @Order(233)
    @DisplayName("[lifecycle] a compact-element type carrying hooks does NOT compose them (no sub-path) and does not throw")
    void nestedLifecycle_compactElement_doesNotComposeHooks() throws IOException {
        final Config c = open();
        final Dtos.CompactHookedPojo p = new Dtos.CompactHookedPojo(7);
        final Dtos.CompactHookedPojo q = new Dtos.CompactHookedPojo(8);
        c.setValue("points", Arrays.asList(p, q)); // serializes compact: one string per element, no sub-tree
        assertTrue(p.fires.isEmpty(), p.fires.toString());
        assertTrue(q.fires.isEmpty(), q.fires.toString());
        c.save();

        final Config r = open();
        final List<Dtos.CompactHookedPojo> read = r.getList("points", Dtos.CompactHookedPojo.class);
        assertEquals(2, read.size());
        assertEquals(7, read.get(0).n);
        assertEquals(8, read.get(1).n);
        for (final Dtos.CompactHookedPojo x : read) {
            assertTrue(x.fires.isEmpty(), x.fires.toString());
        }
    }

    @Test
    @Order(234)
    @DisplayName("[lifecycle] a null element in a hook-bearing list does not break the walk")
    void nestedLifecycle_nullElement_isSkipped() throws IOException {
        assumeNullSupported();
        final Config c = open();
        final Dtos.HookedPojo a = new Dtos.HookedPojo("a", 1, "eA");
        final List<Dtos.HookedPojo> list = new ArrayList<Dtos.HookedPojo>();
        list.add(a);
        list.add(null);
        c.setValue("mixed", list);
        assertTrue(a.fires.contains("postSave@mixed[0]"), a.fires.toString()); // the null contributed no hook
        c.save();

        final Config r = open();
        final List<Dtos.HookedPojo> read = r.getList("mixed", Dtos.HookedPojo.class);
        assertEquals("eA", read.get(0).extra);
    }

    /**
     * Strings a schemaless format could re-read as a number, boolean or null if it emits them unquoted.
     * Each is user data that must come back as the exact same text — an id, a version, a code. The list
     * covers the YAML 1.1 resolver's whole surface (octal via a leading zero, hex, sexagesimal, digit
     * separators, exponents) plus the plain decimal forms whose canonical number spelling differs from
     * the text ({@code 1.10} -> {@code 1.1}, {@code +55} -> {@code 55}).
     */
    private static final List<String> AMBIGUOUS_SCALAR_STRINGS = Arrays.asList(
            "176543210987654321",     // a Discord-style id: fits a long, spells back identically
            "9999999999999999999999", // wider than a long
            "0176",                   // leading zero -> YAML 1.1 octal
            "007",
            "00",
            "0",
            "1.10",                   // trailing zero: the number spells back as "1.1"
            "1.0",
            "3.14",
            "1e5",                    // exponent form
            "0x1F",                   // YAML 1.1 hex
            "12_34",                  // YAML 1.1 digit separator
            "+55",                    // leading plus
            "-7",
            ".5",
            "1:30",                   // YAML 1.1 sexagesimal
            "true",
            "no",
            "null",
            "~",
            "hello");

    /**
     * Inputs the YAML codec currently CORRUPTS: Jackson's {@code MINIMIZE_QUOTES} emits a number-like
     * String unquoted, and re-reading the document resolves it back to a number whose canonical spelling
     * differs from the original text ({@code "1.10"} -> {@code "1.1"}). Skipped for YAML only; every other
     * codec asserts the whole list, because a typed format always delimits a string.
     *
     * <p>DELETE this list and its {@code skipKnownYamlDefect} call once the upstream emitter quotes an
     * ambiguous scalar — {@code YamlQuotingCanaryTest} turns red on that day and says the same thing.
     */
    private static final List<String> YAML_VALUE_DEFECT = Arrays.asList(
            "1.10", "1e5", "0x1F", "12_34", "+55", ".5");

    /**
     * Inputs the YAML codec currently RE-TYPES: emitted unquoted, so the node comes back numeric. A
     * superset of {@link #YAML_VALUE_DEFECT} — the extra entries keep their text only because the resolved
     * number happens to spell back identically, which is luck, not preservation.
     *
     * <p>DELETE together with {@link #YAML_VALUE_DEFECT}.
     */
    private static final List<String> YAML_TYPE_DEFECT = Arrays.asList(
            "176543210987654321", "9999999999999999999999", "0", "1.0", "3.14", "-7",
            "1.10", "1e5", "0x1F", "12_34", "+55", ".5");

    /**
     * True when {@code input} is a known-defective case for the codec under test, so the assertion is
     * bypassed. The value is still written and read — only the check is skipped — so the surrounding
     * inputs still prove they survive alongside it.
     *
     * <p>DELETE this method with the two lists above.
     */
    private boolean skipKnownYamlDefect(final List<String> defect, final String input) {
        return "yaml".equals(fileExtension()) && defect.contains(input);
    }

    /** Reports every case at once (not just the first), so one run maps the whole failure surface. */
    private String describeFailures(final String what, final List<String> failures) {
        final StringBuilder sb = new StringBuilder();
        sb.append(failures.size()).append('/').append(AMBIGUOUS_SCALAR_STRINGS.size())
                .append(' ').append(what).append(" [codec=").append(fileExtension()).append("]:");
        for (final String f : failures) {
            sb.append("\n    ").append(f);
        }
        return sb.toString();
    }

    @Test
    @Order(240)
    @DisplayName("[scalar] a String that reads like a number/bool/null survives a save+reload as the same text")
    void ambiguousString_survivesRoundTrip() throws IOException {
        final Config c = open();
        for (int i = 0; i < AMBIGUOUS_SCALAR_STRINGS.size(); i++) {
            c.setValue("s" + i, AMBIGUOUS_SCALAR_STRINGS.get(i));
        }
        c.save();

        final Config r = open();
        final List<String> lost = new ArrayList<String>();
        for (int i = 0; i < AMBIGUOUS_SCALAR_STRINGS.size(); i++) {
            final String in = AMBIGUOUS_SCALAR_STRINGS.get(i);
            if (skipKnownYamlDefect(YAML_VALUE_DEFECT, in)) {
                continue;
            }
            final String out = r.getValue("s" + i, String.class);
            if (!in.equals(out)) {
                lost.add("'" + in + "' -> '" + out + "'");
            }
        }
        assertTrue(lost.isEmpty(), describeFailures("value(s) MUTATED by a save+reload", lost));
    }

    @Test
    @Order(241)
    @DisplayName("[scalar] a String stays a textual node across a save+reload (the type is not re-inferred)")
    void ambiguousString_keepsTextualNode() throws IOException {
        final Config c = open();
        for (int i = 0; i < AMBIGUOUS_SCALAR_STRINGS.size(); i++) {
            c.setValue("s" + i, AMBIGUOUS_SCALAR_STRINGS.get(i));
        }
        c.save();

        final Config r = open();
        final List<String> retyped = new ArrayList<String>();
        for (int i = 0; i < AMBIGUOUS_SCALAR_STRINGS.size(); i++) {
            if (skipKnownYamlDefect(YAML_TYPE_DEFECT, AMBIGUOUS_SCALAR_STRINGS.get(i))) {
                continue;
            }
            final JsonNode node = r.getRoot().get("s" + i);
            if (node == null || !node.isTextual()) {
                retyped.add("'" + AMBIGUOUS_SCALAR_STRINGS.get(i) + "' -> "
                        + (node == null ? "(absent)" : node.getNodeType() + " " + node));
            }
        }
        assertTrue(retyped.isEmpty(), describeFailures("String(s) RE-TYPED by a save+reload", retyped));
    }

    @Test
    @Order(242)
    @DisplayName("[scalar] an ambiguous String survives inside a list too (the element path, not just a field)")
    void ambiguousString_survivesInsideList() throws IOException {
        final Config c = open();
        c.setValue("ids", AMBIGUOUS_SCALAR_STRINGS);
        c.save();

        final Config r = open();
        final List<String> back = r.getList("ids", String.class);
        final List<String> lost = new ArrayList<String>();
        for (int i = 0; i < AMBIGUOUS_SCALAR_STRINGS.size(); i++) {
            final String in = AMBIGUOUS_SCALAR_STRINGS.get(i);
            if (skipKnownYamlDefect(YAML_VALUE_DEFECT, in)) {
                continue;
            }
            final String out = i < back.size() ? back.get(i) : "(absent)";
            if (!in.equals(out)) {
                lost.add("'" + in + "' -> '" + out + "'");
            }
        }
        assertTrue(lost.isEmpty(), describeFailures("list element(s) MUTATED by a save+reload", lost));
    }

    @Test
    @Order(243)
    @DisplayName("[scalar] a real number is still emitted unquoted and reads back as a number")
    void realNumber_staysNumeric() throws IOException {
        final Config c = open();
        c.setValue("anInt", 42);
        c.setValue("aLong", 176543210987654321L);
        c.setValue("aDouble", 3.14);
        c.save();

        final Config r = open();
        assertTrue(r.getRoot().get("anInt").isNumber(), "anInt re-read as " + r.getRoot().get("anInt").getNodeType());
        assertTrue(r.getRoot().get("aLong").isNumber(), "aLong re-read as " + r.getRoot().get("aLong").getNodeType());
        assertTrue(r.getRoot().get("aDouble").isNumber(), "aDouble re-read as " + r.getRoot().get("aDouble").getNodeType());
        assertEquals(42, r.getValue("anInt", Integer.class).intValue());
        assertEquals(176543210987654321L, r.getValue("aLong", Long.class).longValue());
        assertEquals(3.14, r.getValue("aDouble", Double.class).doubleValue(), 1e-9);
    }

    // ===================== legacy indexed-object list (read tolerance) =====================

    private static Dtos.PlainPojo plainPojo(final String name, final int count, final boolean active) {
        final Dtos.PlainPojo p = new Dtos.PlainPojo();
        p.name = name;
        p.count = count;
        p.active = active;
        p.ratio = 1.5;
        p.epoch = 42L;
        return p;
    }

    @Test
    @Order(250)
    @DisplayName("[legacy] a list stored as an object keyed by numeric indexes reads back via getList")
    void legacyIndexedObject_readsAsList() throws IOException {
        final Config c = open();
        // The old storage persisted a list as an object keyed by throwaway indexes ('0','1',...). Build that
        // shape directly (inserting out of index order to prove the read sorts by index, not insertion order).
        c.setValue("products.1", plainPojo("second", 2, true));
        c.setValue("products.0", plainPojo("first", 1, false));
        assertTrue(c.getNode("products") instanceof ObjectNode, "precondition: legacy shape is an object");

        final List<Dtos.PlainPojo> products = c.getList("products", Dtos.PlainPojo.class);
        assertEquals(2, products.size(), "the legacy indexed object should read as a 2-element list");
        assertEquals("first", products.get(0).name, "element 0 must be the '0'-keyed entry (index order)");
        assertEquals("second", products.get(1).name);
        assertEquals(1, products.get(0).count);
        assertFalse(products.get(0).active);
    }

    @Test
    @Order(251)
    @DisplayName("[legacy] an object with a non-numeric key is NOT mistaken for a list")
    void nonNumericKeyedObject_notReadAsList() {
        final Config c = open();
        c.setValue("things.alpha", plainPojo("a", 1, false));
        c.setValue("things.beta", plainPojo("b", 2, true));
        // A genuine map-shaped object (or nested POJO) must never be cannibalized into a list.
        assertTrue(c.getList("things", Dtos.PlainPojo.class).isEmpty(),
                "a non-numeric-keyed object must not be reinterpreted as a legacy list");
        // A mixed object (one numeric key, one not) also fails the all-numeric gate.
        c.setValue("mixed.0", plainPojo("a", 1, false));
        c.setValue("mixed.tag", plainPojo("b", 2, true));
        assertTrue(c.getList("mixed", Dtos.PlainPojo.class).isEmpty(),
                "a mixed-key object must not be reinterpreted as a legacy list");
    }

    @Test
    @Order(252)
    @DisplayName("[legacy] reading a legacy list then saving migrates it to the modern array form")
    void legacyIndexedObject_migratesOnSave() throws IOException {
        final Config c = open();
        c.setValue("products.0", plainPojo("first", 1, false));
        c.setValue("products.1", plainPojo("second", 2, true));

        final List<Dtos.PlainPojo> products = c.getList("products", Dtos.PlainPojo.class);
        c.setValue("products", products); // write-back follows the modern pattern (a real sequence)
        c.save();

        final Config r = open();
        assertTrue(r.getNode("products") instanceof ArrayNode,
                "a save must re-emit the list as a modern array, not the legacy indexed object");
        assertEquals(2, r.getList("products", Dtos.PlainPojo.class).size());
    }

    @Test
    @Order(260)
    @DisplayName("[spacing] a list element's blank lines are emitted above it and survive a round-trip")
    void listElementBlankLines_roundTrip() throws IOException {
        assumeListItemComments();
        final Config c = open();
        c.setValue("tags", Arrays.asList("alpha", "beta"));
        c.setComment("tags.1", "the fallback tag");
        c.setBlankLinesBefore("tags.1", 1);
        c.save();

        final String first = readText();
        assertBlankLineAbove(first, "the fallback tag");

        open().save(); // a plain load/save must re-emit the very same bytes
        assertEquals(first, readText(), "an element's spacing must round-trip:\n" + first);
    }

    @Test
    @Order(261)
    @DisplayName("[spacing] a list with blank lines but no comment at all still renders item by item")
    void listElementBlankLines_withoutAnyComment() throws IOException {
        assumeListItemComments();
        final Config c = open();
        c.setValue("tags", Arrays.asList("alpha", "beta"));
        c.setBlankLinesBefore("tags.1", 1);
        c.save();

        final String first = readText();
        // without the extended gate the list would dump inline and the spacing would be lost
        assertBlankLineAbove(first, "beta");

        open().save();
        assertEquals(first, readText(), "spacing alone must keep the item-by-item form:\n" + first);
    }

    // ===================== comment spacing (policy + directive) =====================

    @Test
    @Order(270)
    @DisplayName("[spacing] with the policy off a load/save is byte-identical")
    void spacingPolicyOff_loadSaveIsByteIdentical() throws IOException {
        assumeComments();
        final Config c = open();
        c.setHeader("a header");
        c.setValue("alpha", 1);
        c.setComment("alpha", "documented");
        c.setValue("beta.inner", 2);
        c.setComment("beta", "a section");
        c.setValue("tags", Arrays.asList("x", "y"));
        c.setFooter("the end");
        c.save();

        final String first = readText();
        open().save();
        assertEquals(first, readText(), "the default policy must not rewrite a file:\n" + first);
    }

    @Test
    @Order(271)
    @DisplayName("[spacing] a cushioned comment block round-trips byte-for-byte (the parser never normalizes)")
    void cushionedBlock_survivesLoadSave() throws IOException {
        assumeLosslessComments();
        writeText(cushionedFixture());
        final String before = normalize(readText());
        open().save();
        assertEquals(before, normalize(readText()), "load/save must not rewrite a cushioned block");
    }

    @Test
    @Order(272)
    @DisplayName("[spacing] blank lines the policy emitted are file data afterwards (the ratchet)")
    void spacingPolicy_ratchetsIntoTheFile() throws IOException {
        assumeComments();
        final Config c = open().withBlankLineBeforeComments(1);
        c.setValue("alpha", 1);
        c.setValue("beta", 2);
        c.setComment("beta", "documented");
        c.save();

        final String withPolicy = readText();
        open().save(); // reopened WITHOUT the policy: the blank lines stay, they are the file's now
        assertEquals(withPolicy, readText(), "turning the policy off must not remove what it emitted");
    }

    @Test
    @Order(273)
    @DisplayName("[spacing] below a header the first key keeps only the header's own separator")
    void header_firstCommentedKeyIsNotFloored() throws IOException {
        assumeComments();
        final Config c = open().withBlankLineBeforeComments(1);
        c.setHeader("a header");
        c.setValue("alpha", 1);
        c.setComment("alpha", "documented");
        c.save();

        final String first = readText();
        assertEquals(0, open().getBlankLinesBefore("alpha"),
                "the header separator is not the key's own spacing:\n" + first);
        open().save();
        assertEquals(first, readText());
    }

    @Test
    @Order(274)
    @DisplayName("[spacing] below a header a directive on the first key survives the round-trip")
    void header_firstKeyDirectiveRoundTrips() throws IOException {
        assumeComments();
        final Config c = open().withBlankLineBeforeComments(1);
        c.setHeader("a header");
        c.setValue("alpha", 1);
        c.setComment("alpha", "\n\n\ndocumented");
        c.save();

        final String first = readText();
        assertEquals(3, open().getBlankLinesBefore("alpha"),
                "the header separator must not be counted into the key's spacing:\n" + first);
        open().save();
        assertEquals(first, readText());
    }

    @Test
    @Order(275)
    @DisplayName("[spacing] a header above an uncommented first key round-trips unchanged")
    void header_uncommentedFirstKeyRoundTrips() throws IOException {
        assumeComments();
        final Config c = open().withBlankLineBeforeComments(1);
        c.setHeader("a header");
        c.setValue("alpha", 1);
        c.save();

        final String first = readText();
        assertEquals(0, open().getBlankLinesBefore("alpha"));
        open().save();
        assertEquals(first, readText());
    }

    @Test
    @Order(276)
    @DisplayName("[spacing] leading blank lines with no header do not become a phantom header")
    void noHeader_leadingBlanksBelongToTheFirstKey() throws IOException {
        assumeComments();
        final Config c = open();
        c.setValue("alpha", 1);
        c.setComment("alpha", "\n\ndocumented");
        c.save();

        final String first = readText();
        final Config r = open();
        assertTrue(r.getCommentTree().getHeader().isEmpty(),
                "blank lines before the first comment are not a header:\n" + first);
        assertEquals(2, r.getBlankLinesBefore("alpha"));
        open().save();
        assertEquals(first, readText());
    }

    @Test
    @Order(277)
    @DisplayName("[spacing] the policy floors a commented root key, and only that")
    void spacingPolicy_floorsCommentedRootKeysOnly() throws IOException {
        assumeComments();
        final Config c = open().withBlankLineBeforeComments(1);
        c.setValue("alpha", 1);
        c.setComment("alpha", "the first key");
        c.setValue("beta", 2);                       // no comment
        c.setValue("gamma", 3);
        c.setComment("gamma", "documented");
        c.setValue("delta.inner", 4);
        c.setComment("delta.inner", "nested doc");   // depth 2, out of the default reach
        c.save();

        final String text = readText();
        final Config r = open();
        assertEquals(0, r.getBlankLinesBefore("alpha"), "the first entry emitted never floats:\n" + text);
        assertEquals(0, r.getBlankLinesBefore("beta"), "an uncommented key never floats:\n" + text);
        assertEquals(1, r.getBlankLinesBefore("gamma"), "a commented root key floats:\n" + text);
        assertEquals(0, r.getBlankLinesBefore("delta.inner"), "depth 2 is out of maxDepth 1:\n" + text);
    }

    @Test
    @Order(278)
    @DisplayName("[spacing] the policy reaches exactly as deep as it was told to")
    void spacingPolicy_reachesTheConfiguredDepth() throws IOException {
        assumeComments();
        final Config c = open().withBlankLineBeforeComments(1, 2);
        c.setValue("outer.first", 1);
        c.setValue("outer.second", 2);
        c.setComment("outer.second", "depth 2");
        c.setValue("outer.deep.first", 3);
        c.setValue("outer.deep.second", 4);
        c.setComment("outer.deep.second", "depth 3");
        c.save();

        final String text = readText();
        final Config r = open();
        assertEquals(1, r.getBlankLinesBefore("outer.second"), "depth 2 is within reach:\n" + text);
        assertEquals(0, r.getBlankLinesBefore("outer.deep.second"), "depth 3 is not:\n" + text);
    }

    @Test
    @Order(279)
    @DisplayName("[spacing] a directive applies with the policy both on and off")
    void directive_appliesRegardlessOfThePolicy() throws IOException {
        assumeComments();
        final Config c = open();
        c.setValue("alpha", 1);
        c.setValue("beta", 2);
        c.setComment("beta", "\n\ndocumented");
        assertEquals(2, c.getBlankLinesBefore("beta"), "the directive is peeled off at write time");
        assertEquals("documented", c.getComment("beta"), "the empty lines are spacing, not comment text");
        c.save();
        assertEquals(2, open().getBlankLinesBefore("beta"));

        open().withBlankLineBeforeComments(1).save();
        assertEquals(2, open().getBlankLinesBefore("beta"), "a wider directive is not tightened by the policy");
    }

    @Test
    @Order(280)
    @DisplayName("[spacing] a directive on a nested POJO field lands at its own sub-path")
    void directive_onANestedAnnotatedField() throws IOException {
        assumeComments();
        final Config c = open();
        c.mergeValue("", new Dtos.SpacingDirectivePojo());
        c.save();

        final Config r = open();
        assertEquals(2, r.getBlankLinesBefore("section.second"));
        assertEquals("Two blank lines float above this one.", r.getComment("section.second"));
        assertEquals(0, r.getBlankLinesBefore("section.first"));
    }

    @Test
    @Order(281)
    @DisplayName("[spacing] an empty line in the MIDDLE of a comment still emits a bare marker")
    void interiorEmptyLine_staysABareMarker() throws IOException {
        assumeComments();
        final Config c = open();
        c.setValue("alpha", 1);
        c.setComment("alpha", "first\n\nsecond");
        c.save();

        final String text = readText();
        assertConsecutiveTrimmedLines(text,
                commentMarker() + " first", commentMarker(), commentMarker() + " second");
        assertEquals(0, open().getBlankLinesBefore("alpha"), "an interior empty line is not a directive");
    }

    @Test
    @Order(282)
    @DisplayName("[spacing] an all-empty comment text keeps its bare-marker meaning and is byte-stable")
    void allEmptyComment_isNotADirective() throws IOException {
        assumeComments();
        final Config c = open();
        c.setValue("alpha", 1);
        c.setComment("alpha", "");
        c.save();

        final String first = readText();
        assertEquals(0, open().getBlankLinesBefore("alpha"), "an all-empty text never becomes spacing");
        open().save();
        assertEquals(first, readText(), "an empty comment must round-trip:\n" + first);
    }

    @Test
    @Order(283)
    @DisplayName("[spacing] where a policy and a directive meet, the wider one wins")
    void policyAndDirective_theWiderWins() throws IOException {
        assumeComments();
        final Config c = open().withBlankLineBeforeComments(3);
        c.setValue("alpha", 1);
        c.setValue("beta", 2);
        c.setComment("beta", "\ndocumented");            // directive 1 vs policy 3
        c.setValue("gamma", 3);
        c.setComment("gamma", "\n\n\n\n\ndocumented");    // directive 5 vs policy 3
        c.save();

        final String text = readText();
        final Config r = open();
        assertEquals(3, r.getBlankLinesBefore("beta"), "the policy raises a narrower directive:\n" + text);
        assertEquals(5, r.getBlankLinesBefore("gamma"), "the policy never tightens a wider one:\n" + text);
    }

    @Test
    @Order(284)
    @DisplayName("[spacing] rewriting an OVERRIDE comment without a directive keeps the file's own spacing")
    void overrideComment_withoutDirective_keepsSpacing() throws IOException {
        assumeComments();
        final Config seed = open();
        seed.setValue("alpha", 1);
        seed.setBlankLinesBefore("alpha", 3);
        seed.save();

        final Config c = open();
        c.mergeValue("", new Dtos.OverrideCommentPojo()); // OVERRIDE rewrites the text, not the spacing
        c.save();

        assertEquals(3, open().getBlankLinesBefore("alpha"), "hand-made separation must survive a reseed");
        assertEquals("Documented.", open().getComment("alpha"));
    }

    @Test
    @Order(285)
    @DisplayName("[spacing] an OVERRIDE comment WITH a directive owns the spacing, even downwards")
    void overrideComment_withDirective_ownsSpacing() throws IOException {
        assumeComments();
        final Config seed = open();
        seed.setValue("alpha", 1);
        seed.setBlankLinesBefore("alpha", 3);
        seed.save();

        final Config c = open();
        c.mergeValue("", new Dtos.OverrideDirectivePojo());
        c.save();

        assertEquals(1, open().getBlankLinesBefore("alpha"), "with a directive the author owns the spacing");
    }

    @Test
    @Order(286)
    @DisplayName("[spacing] setBlankLinesBefore(path, 0) clears what a directive wrote")
    void setBlankLinesBeforeZero_clearsTheDirective() throws IOException {
        assumeComments();
        final Config c = open();
        c.setValue("alpha", 1);
        c.setValue("beta", 2);
        c.setComment("beta", "\n\ndocumented");
        c.setBlankLinesBefore("beta", 0);
        c.save();

        assertEquals(0, open().getBlankLinesBefore("beta"));
        assertEquals("documented", open().getComment("beta"));
    }

    @Test
    @Order(287)
    @DisplayName("[spacing] save/load/save is idempotent for every policy-directive combination")
    void spacing_isIdempotentInEveryCombination() throws IOException {
        assumeComments();
        for (int policy = 0; policy <= 1; policy++) {
            for (int directive = 0; directive <= 1; directive++) {
                deleteQuietly(file());
                final Config c = open().withBlankLineBeforeComments(policy);
                c.setValue("alpha", 1);
                c.setValue("beta", 2);
                c.setComment("beta", directive == 1 ? "\n\ndocumented" : "documented");
                c.save();

                final String first = readText();
                open().withBlankLineBeforeComments(policy).save();
                assertEquals(first, readText(),
                        "policy=" + policy + " directive=" + directive + " must re-emit the same bytes:\n" + first);
            }
        }
    }

    @Test
    @Order(288)
    @DisplayName("[spacing] the acceptance fixture matches its golden layout byte-for-byte")
    void spacingLayout_matchesGolden() throws IOException {
        assertSpacingGoldenLayout();
    }

    /** Asserts {@code expected} appears as consecutive lines of {@code text}, compared trimmed so a codec's
     *  indentation does not enter the assertion. */
    private static void assertConsecutiveTrimmedLines(final String text, final String... expected) {
        final String[] lines = normalize(text).split("\n", -1);
        for (int i = 0; i + expected.length <= lines.length; i++) {
            boolean hit = true;
            for (int j = 0; j < expected.length && hit; j++) {
                hit = lines[i + j].trim().equals(expected[j]);
            }
            if (hit) {
                return;
            }
        }
        fail("expected the consecutive lines " + Arrays.toString(expected) + " in:\n" + text);
    }

    /** Asserts the line carrying {@code needle} has an empty line directly above it. */
    private static void assertBlankLineAbove(final String text, final String needle) {
        final String[] lines = normalize(text).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                assertTrue(i > 0 && lines[i - 1].isEmpty(),
                        "expected a blank line directly above the line carrying '" + needle + "':\n" + text);
                return;
            }
        }
        fail("no line carrying '" + needle + "' in:\n" + text);
    }

    private static boolean waitUntil(final BooleanSupplier cond, final long timeoutMs) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20L);
        }
        return cond.getAsBoolean();
    }

    // ============================================================================
    //  Semantic rules around the bind
    // ============================================================================

    /** One bounded value plus one the file can break by coercion, recording what each hook was handed. */
    public static class RuledDto implements LoadIssueAware {

        @TestMax(100)
        public int limit = 5;

        public int port = 25565;

        @JsonIgnore
        public List<LoadIssue> postLoadIssues = Collections.emptyList();

        @JsonIgnore
        public List<LoadIssue> awareIssues = Collections.emptyList();

        @PostLoad
        void afterLoad(final ConfigContext context) {
            postLoadIssues = new ArrayList<LoadIssue>(context.issues());
        }

        @Override
        public void setLoadIssues(final List<LoadIssue> issues) {
            awareIssues = new ArrayList<LoadIssue>(issues);
        }
    }

    /** Its rule clamps instead of only complaining, so a write shows the normalization in the file itself. */
    public static class ClampedDto {

        @TestMax(value = 100, correctTo = 100)
        public int limit = 250;

        @JsonIgnore
        public List<LoadIssue> postSaveIssues = Collections.emptyList();

        @PostSave
        void afterSave(final ConfigContext context) {
            postSaveIssues = new ArrayList<LoadIssue>(context.issues());
        }
    }

    /** A rule broken by the file, reported rather than thrown, so a load can be inspected afterwards. */
    private Config openWithBrokenLimit() {
        final Config seed = open();
        seed.setValue("limit", 150);
        seed.setValue("port", "not-a-number");
        seed.save();
        return open();
    }

    /**
     * Rules around the bind, on every codec: they run inside the bind, between it and the hooks, and what
     * they find travels the SAME channel the coercion issues already travel — so a validator written before
     * rules existed sees them without one line of change.
     */
    @Nested
    @DisplayName("[rules] the bind applies the config's rules")
    class RuleBindContractTest {

        @Test
        @DisplayName("VALIDATE runs between the bind and @PostLoad, and @PostLoad sees the rule issue")
        void validateRunsBetweenBindAndPostLoad() {
            final Config c = openWithBrokenLimit();
            final BindResult<RuledDto> result = c.loadAsResult(RuledDto.class, codec);

            final LoadIssue rule = onlyRuleIssue(result.issues());
            assertEquals("limit", rule.key());
            assertEquals("must be at most 100", rule.message());
            assertNotNull(rule.violation());

            // the hook was handed the finished list, rule issue included: VALIDATE had already run
            assertTrue(result.value().postLoadIssues.contains(rule),
                    "expected @PostLoad to see the rule issue, saw " + result.value().postLoadIssues);
        }

        @Test
        @DisplayName("LoadIssueAware and the *Result reads carry coercion and rule issues in one list")
        void bothKindsTravelTogether() {
            final Config c = openWithBrokenLimit();
            final BindResult<RuledDto> result = c.bind(RuledDto.class, codec).readResult("");

            assertEquals(2, result.issues().size(), "expected one coercion + one rule issue, got "
                    + result.issues());
            assertEquals(1, countOfKind(result.issues(), LoadIssue.Kind.COERCION));
            assertEquals(1, countOfKind(result.issues(), LoadIssue.Kind.RULE));
            assertEquals(result.issues(), result.value().awareIssues);

            // loadAsResult carries the same two
            final BindResult<RuledDto> viaLoadAs = c.loadAsResult(RuledDto.class, codec);
            assertEquals(1, countOfKind(viaLoadAs.issues(), LoadIssue.Kind.COERCION));
            assertEquals(1, countOfKind(viaLoadAs.issues(), LoadIssue.Kind.RULE));
        }

        @Test
        @DisplayName("NORMALIZE runs before the projection, and @PostSave receives what it found")
        void normalizeRunsBeforeTheProjection() throws IOException {
            final Config c = open();
            c.withRulePolicy(RulePolicy.defaults()
                    .withCorrections(true)
                    .withDefaultViolations(RulePolicy.Severity.REPORT));

            final ClampedDto dto = new ClampedDto();
            c.bind(ClampedDto.class, codec).write("", dto);
            c.save();

            assertEquals(100, dto.limit, "the normalization must reach the POJO before it is projected");
            assertEquals(100, open().getInt("limit"), "the clamped value is what the file received");
            assertEquals(1, dto.postSaveIssues.size(), "expected @PostSave to receive the NORMALIZE issue, got "
                    + dto.postSaveIssues);
            assertEquals(LoadIssue.Kind.RULE, dto.postSaveIssues.get(0).kind());
        }

        @Test
        @DisplayName("a config with no candidate annotation round-trips byte-identically and reports nothing")
        void inertiaForAConfigWithoutRules() throws IOException {
            final Config c = open();
            final Dtos.PlainPojo plain = new Dtos.PlainPojo();
            plain.name = "keep";
            plain.count = 7;
            c.setValue("simple", plain);
            c.save();
            final String first = readText();

            final Config reopened = open();
            final BindResult<Dtos.PlainPojo> result =
                    reopened.bind(Dtos.PlainPojo.class, codec).readResult("simple");
            assertTrue(result.issues().isEmpty(), "a rule-free config must report nothing, got "
                    + result.issues());
            assertFalse(reopened.hasRuleFixes());

            reopened.bind(Dtos.PlainPojo.class, codec).write("simple", result.value());
            reopened.save();
            assertEquals(first, readText(), "the attached default engine must not change a single byte");
        }
    }

    private static LoadIssue onlyRuleIssue(final List<LoadIssue> issues) {
        LoadIssue found = null;
        for (final LoadIssue issue : issues) {
            if (issue.kind() == LoadIssue.Kind.RULE) {
                assertNull(found, "expected exactly one rule issue in " + issues);
                found = issue;
            }
        }
        assertNotNull(found, "expected a rule issue in " + issues);
        return found;
    }

    private static int countOfKind(final List<LoadIssue> issues, final LoadIssue.Kind kind) {
        int count = 0;
        for (final LoadIssue issue : issues) {
            if (issue.kind() == kind) {
                count++;
            }
        }
        return count;
    }
}
