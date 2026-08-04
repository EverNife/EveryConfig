package br.com.finalcraft.everyconfig.rule;

import br.com.finalcraft.everyconfig.annotation.Comment;
import br.com.finalcraft.everyconfig.codec.jackson.YamlCodec;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.core.comment.CommentType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The padlock on the path grammar: a rule site addresses exactly where the entity's value — and its
 * documentation — actually lands. Rule resolution, the value write and the comment seeding share one
 * helper, and this suite is what keeps them from ever drifting apart again.
 */
class RuleModelTest {

    private final YamlCodec yaml = new YamlCodec();

    /** The written tree, with the fixture's comments seeded exactly as a real save would seed them. */
    private Config written() {
        final Config config = Config.inMemory(yaml);
        config.bind(RuleFixtures.Hairy.class, yaml).write("", new RuleFixtures.Hairy());
        return config;
    }

    @Test
    void everyFieldSiteSitsWhereItsCommentIsSeeded() {
        final Config config = written();
        int checked = 0;
        for (final RuleSite site : RuleModel.of(RuleFixtures.Hairy.class)) {
            if (site.kind() != RuleSite.Kind.FIELD) {
                continue;
            }
            final Comment comment = site.field().getAnnotation(Comment.class);
            assertNotNull(comment, site.toString());
            assertEquals(String.join("\n", comment.value()),
                    config.getCommentTree().getComment(site.path(), CommentType.BLOCK),
                    "comment seeded away from the site of " + site);
            assertEquals(Arrays.asList(comment.value()), site.comment());
            checked++;
        }
        assertEquals(9, checked);
    }

    @Test
    void everyFieldSitePathAddressesTheWrittenValue() {
        final Config config = written();
        final List<String> paths = new ArrayList<>();
        for (final RuleSite site : RuleModel.of(RuleFixtures.Hairy.class)) {
            if (site.kind() == RuleSite.Kind.FIELD) {
                assertNotNull(config.getNode(site.path()), "no value at the site of " + site);
                paths.add(site.path());
            }
        }
        assertEquals(Arrays.asList("plain", "max-pool-size", "json-named", "database.pool.maxSize",
                "one.mid", "one.two.deep", "left.tag", "right.tag", "self.name"), paths);
    }

    @Test
    void aSitePathIsTheKeyTheFileIsWrittenWith() {
        final Config config = written();
        final String emitted = yaml.writeWithComments(config.getRoot(), config.getCommentTree(),
                config.getFileKeyOrder());
        assertTrue(emitted.contains("max-pool-size:"), emitted);
        assertTrue(emitted.contains("# sectioned"), emitted);
        assertTrue(emitted.contains("# leaf tag"), emitted);
    }
}
