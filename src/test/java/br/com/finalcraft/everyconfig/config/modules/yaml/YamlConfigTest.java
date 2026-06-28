package br.com.finalcraft.everyconfig.config.modules.yaml;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.jackson.YamlCodec;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.modules.AbstractConfigTest;
import br.com.finalcraft.everyconfig.core.comment.CommentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The config contract over the YAML codec (fidelity LOSSLESS: the full comment-aware path runs). */
@DisplayName("YamlConfig (fidelity=LOSSLESS, comment-aware)")
class YamlConfigTest extends AbstractConfigTest {

    @Override
    protected Codec newCodec() {
        return new YamlCodec();
    }

    @Override
    protected String fileExtension() {
        return "yaml";
    }

    @Override
    protected CommentFidelity fidelity() {
        return CommentFidelity.LOSSLESS;
    }

    @Override
    protected boolean supportsListItemComments() {
        return true; // YAML is the codec that round-trips per-element scalar-list comments
    }

    @Override
    protected String malformedText() {
        return "a: [1, 2\nb: : :";
    }

    @Test
    @Order(300)
    @DisplayName("[yaml] a per-element comment is emitted immediately above its list item")
    void listItemComment_emittedAboveElement() throws IOException {
        final Config c = open();
        c.setValue("tags", Arrays.asList("alpha", "beta"));
        c.setComment("tags.0", "the primary tag");
        c.save();

        final String text = readText();
        assertTrue(text.contains("  # the primary tag\n  - alpha"),
                "the element comment should sit directly above its item:\n" + text);
    }

    @Test
    @Order(301)
    @DisplayName("[yaml] the emitted layout matches the golden fixture byte-for-byte")
    void goldenLayout_byteStable() throws IOException {
        assertGoldenLayout();
    }

    @Test
    @Order(302)
    @DisplayName("[yaml] a '#' line inside a | block scalar is literal text, not a comment on the next key")
    void blockScalar_bodyHashIsNotAComment() throws IOException {
        final String yaml = "banner: |\n"
                + "  Welcome to the server\n"
                + "  #####  ASCII banner  #####\n"
                + "name: production\n";
        writeText(yaml);

        final Config c = open();
        // data is correct (the '#' line is literal content of the block)
        assertEquals("production", c.getString("name"));
        assertTrue(c.getString("banner").contains("ASCII banner"));
        // the '#' inside the block must NOT bleed onto 'name' as a spurious comment
        assertNull(c.getComment("name"));

        // and re-saving must not inject that line as a comment either
        c.save();
        final Config r = open();
        assertEquals("production", r.getString("name"));
        assertTrue(r.getString("banner").contains("ASCII banner"));
        assertNull(r.getComment("name"));
    }

    @Test
    @Order(303)
    @DisplayName("[yaml] a side comment after a multi-line flow mapping belongs to the owning key")
    void wrappedFlowMapping_sideCommentStaysOnOwner() throws IOException {
        final String yaml = "servers: {web: 10.0.0.1,\n"
                + "  db: 10.0.0.2}   # the server pool\n"
                + "name: cluster\n";
        writeText(yaml);

        final Config c = open();
        // data is correct (Jackson parses the flow mapping)
        assertEquals("10.0.0.1", c.getString("servers.web"));
        assertEquals("10.0.0.2", c.getString("servers.db"));
        assertEquals("cluster", c.getString("name"));
        // the side comment is for 'servers', not the inner 'servers.db'
        assertEquals("the server pool", c.getComment("servers", CommentType.SIDE));
        assertNull(c.getComment("servers.db", CommentType.SIDE));

        // it round-trips: re-saving (as a block mapping) keeps the comment on 'servers'
        c.save();
        final Config r = open();
        assertEquals("the server pool", r.getComment("servers", CommentType.SIDE));
    }
}
