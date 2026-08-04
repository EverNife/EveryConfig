package br.com.finalcraft.everyconfig.config.section;

import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.data.Dtos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link ConfigSection} ⇄ {@link Config} pairing: the self accessors (operate on the section's own
 * path) and the sub-path mirrors. The self reads key on a {@link Class} or take no argument, so they read
 * THIS section without the {@code getConfig().getValue(getPath(), type)} detour.
 */
class ConfigSectionPairingTest {

    private static Dtos.PlainPojo samplePojo() {
        final Dtos.PlainPojo p = new Dtos.PlainPojo();
        p.name = "db";
        p.count = 5;
        p.active = true;
        p.ratio = 1.5;
        p.epoch = 100L;
        return p;
    }

    @Test
    void selfTypedReadBindsThisSectionNotAChild() {
        final Config cfg = Config.inMemory();
        final Dtos.PlainPojo p = samplePojo();
        cfg.setValue("srv", p);

        final ConfigSection sec = cfg.getConfigSection("srv");
        // The headline: bind the section's OWN path, no getConfig()/getPath() detour.
        assertEquals(p, sec.getValue(Dtos.PlainPojo.class));
        assertEquals(cfg.getValue("srv", Dtos.PlainPojo.class), sec.getValue(Dtos.PlainPojo.class));
    }

    @Test
    void selfWriteReplacesThisSectionAndRoundTrips() {
        final Config cfg = Config.inMemory();
        final ConfigSection sec = cfg.getConfigSection("srv");
        assertFalse(sec.exists());

        sec.setValue(samplePojo()); // single-arg self write, targets the section itself
        assertTrue(sec.exists());
        assertNotNull(sec.getNode());
        assertEquals(samplePojo(), sec.getValue(Dtos.PlainPojo.class));
    }

    @Test
    void selfListReadBindsThisSection() {
        final Config cfg = Config.inMemory();
        final List<Integer> nums = Arrays.asList(1, 2, 3);
        cfg.setValue("nums", nums);
        assertEquals(nums, cfg.getConfigSection("nums").getList(Integer.class));
    }

    @Test
    void selfRawReadAndExistsOnNativeConfig() {
        final Config cfg = new Config(); // codec-less: native tree values
        cfg.setValue("a.b", 42);
        final ConfigSection sec = cfg.getConfigSection("a.b");
        assertTrue(sec.exists());
        assertEquals(42, sec.getInt());
        assertNotNull(sec.getValue());
        assertFalse(cfg.getConfigSection("a.x").exists());
    }

    @Test
    void selfCommentTargetsThisSection() {
        final Config cfg = new Config();
        cfg.setValue("dbg", 1);
        final ConfigSection sec = cfg.getConfigSection("dbg");
        sec.setComment("debug flag"); // single-arg self comment
        assertEquals("debug flag", sec.getComment());
        assertEquals("debug flag", cfg.getComment("dbg"));
    }

    @Test
    void subPathTypedReadMirror() {
        final Config cfg = Config.inMemory();
        final Dtos.PlainPojo p = samplePojo();
        cfg.setValue("root.srv", p);
        final ConfigSection root = cfg.getConfigSection("root");
        assertEquals(p, root.getValue("srv", Dtos.PlainPojo.class));
    }

    @Test
    void getStringListDefaultMirrorReturnsDefaultWhenAbsent() {
        final Config cfg = new Config();
        final ConfigSection sec = cfg.getConfigSection("s");
        final List<String> def = Arrays.asList("x", "y");
        assertEquals(def, sec.getStringList("missing", def));
    }
}
