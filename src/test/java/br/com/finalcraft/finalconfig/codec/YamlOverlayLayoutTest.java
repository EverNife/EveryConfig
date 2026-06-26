package br.com.finalcraft.finalconfig.codec;

import br.com.finalcraft.finalconfig.codec.CommentAware.CommentLoad;
import br.com.finalcraft.finalconfig.codec.jackson.YamlCodec;
import br.com.finalcraft.finalconfig.core.comment.CommentType;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trip of the non-data layout the overlay carries: file header, footer, and blank-line spacing. */
class YamlOverlayLayoutTest {

    private final YamlCodec codec = new YamlCodec();

    private String reemit(final String yaml) {
        final CommentLoad load = codec.readComments(yaml);
        final JsonNode tree = codec.readTree(yaml);
        return codec.writeWithComments(tree, load.comments, load.keyOrder);
    }

    @Test
    void fileHeaderIsPeeledAndPreserved() {
        final String yaml = "# File header\n# line two\n\nserver:\n  host: localhost\n";
        final CommentLoad load = codec.readComments(yaml);

        // The header is recovered separately, NOT as the first key's block comment.
        assertEquals(Arrays.asList("File header", "line two"), load.comments.getHeader());
        assertNull(load.comments.getComment("server", CommentType.BLOCK));

        final String emitted = reemit(yaml);
        assertTrue(emitted.startsWith("# File header\n# line two\n\nserver:"), emitted);
        // Header survives a second parse.
        assertEquals(Arrays.asList("File header", "line two"),
                codec.readComments(emitted).comments.getHeader());
    }

    @Test
    void adjacentCommentIsTheFirstKeyBlockNotAHeader() {
        // No blank line between the comment and the first key -> it is the key's own block comment.
        final String yaml = "# belongs to server\nserver:\n  host: localhost\n";
        final CommentLoad load = codec.readComments(yaml);
        assertTrue(load.comments.getHeader().isEmpty());
        assertEquals("belongs to server", load.comments.getComment("server", CommentType.BLOCK));
    }

    @Test
    void footerIsPreserved() {
        final String yaml = "server:\n  host: localhost\n\n# trailing footer\n";
        final CommentLoad load = codec.readComments(yaml);
        assertEquals(Collections.singletonList("trailing footer"), load.comments.getFooter());

        final String emitted = reemit(yaml);
        assertTrue(emitted.contains("# trailing footer"), emitted);
        assertEquals(Collections.singletonList("trailing footer"),
                codec.readComments(emitted).comments.getFooter());
    }

    @Test
    void blankLinesBetweenKeysArePreservedExactly() {
        final String yaml = "alpha: 1\n\n\nbeta: 2\n";
        final CommentLoad load = codec.readComments(yaml);
        assertEquals(2, load.comments.getBlankLinesBefore("beta"));
        assertEquals(0, load.comments.getBlankLinesBefore("alpha"));
        // The exact vertical layout round-trips.
        assertEquals(yaml, reemit(yaml));
    }
}
