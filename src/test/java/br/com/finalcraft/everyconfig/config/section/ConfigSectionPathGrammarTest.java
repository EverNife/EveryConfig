package br.com.finalcraft.everyconfig.config.section;

import br.com.finalcraft.everyconfig.config.Config;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A key that legitimately holds the path separator ({@code "eu.west"}) travels the path grammar escaped
 * ({@code servers.eu\.west}). These pin the round-trip: whoever hands out a child section must be able to
 * ask it back for its own key and its parent, and get the literal key and the real parent - not the two
 * halves the escape was there to prevent splitting.
 */
class ConfigSectionPathGrammarTest {

    /** The escaped path form of the single key {@code "eu.west"} nested under {@code "servers"}. */
    private static final String ESCAPED_PATH = "servers.eu\\.west";

    private static Config configWithADottedKey() {
        final Config cfg = Config.inMemory();
        cfg.setValue(ESCAPED_PATH + ".port", 25565);
        return cfg;
    }

    private static ConfigSection onlyChildOfServers(final Config cfg) {
        final Set<ConfigSection> children = cfg.getKeysSections("servers");
        assertEquals(1, children.size(), "the dotted key must be ONE child, not two");
        return children.iterator().next();
    }

    @Test
    void aChildHandedOutByGetKeysSectionsKnowsItsOwnLiteralKey() {
        final ConfigSection child = onlyChildOfServers(configWithADottedKey());

        assertEquals(ESCAPED_PATH, child.getPath(), "the path stays in escaped form");
        assertEquals("eu.west", child.getSectionKey(),
                "the key must come back whole - splitting it is what the escape prevents");
    }

    @Test
    void theParentOfADottedKeyIsNotCutInsideTheEscape() {
        final ConfigSection child = onlyChildOfServers(configWithADottedKey());

        assertEquals("servers", child.getParentSection().getPath(),
                "cutting at the escaped separator would leave a dangling backslash");
    }

    @Test
    void aChildKeyIsUsableToReadBackThroughItsOwnSection() {
        final ConfigSection child = onlyChildOfServers(configWithADottedKey());

        assertEquals(25565, child.getInt("port"), "the section must resolve against the whole key");
    }

    @Test
    void anOrdinaryKeyIsUnaffected() {
        final Config cfg = Config.inMemory();
        cfg.setValue("servers.lobby.port", 25565);

        final ConfigSection child = onlyChildOfServers(cfg);

        assertEquals("lobby", child.getSectionKey());
        assertEquals("servers", child.getParentSection().getPath());
    }

    @Test
    void aRootSectionHasNoKeyAndNoParentPath() {
        final Config cfg = Config.inMemory();
        cfg.setValue("top", 1);

        final ConfigSection top = cfg.getConfigSection("top");
        assertEquals("top", top.getSectionKey());
        assertEquals("", top.getParentSection().getPath());
    }
}
