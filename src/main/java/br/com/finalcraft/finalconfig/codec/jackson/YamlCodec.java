package br.com.finalcraft.finalconfig.codec.jackson;

import br.com.finalcraft.finalconfig.codec.Codec;
import br.com.finalcraft.finalconfig.codec.CodecException;
import br.com.finalcraft.finalconfig.codec.CommentAware;
import br.com.finalcraft.finalconfig.codec.CommentFidelity;
import br.com.finalcraft.finalconfig.codec.FCMapperProfiles;
import br.com.finalcraft.finalconfig.codec.ObjectMapperAware;
import br.com.finalcraft.finalconfig.core.KeyOrder;
import br.com.finalcraft.finalconfig.core.comment.CommentTree;
import br.com.finalcraft.finalconfig.core.comment.CommentType;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * YAML codec ({@link CommentFidelity#LOSSLESS}, {@link CommentAware}). Data parsing and leaf-value
 * serialization go through a Jackson {@link YAMLMapper}, but the document STRUCTURE (keys, indent,
 * sections, comment lines, key order) is rendered by this codec's own emitter — the mapper never sees
 * the whole tree. A user-supplied mapper can therefore restyle a leaf value without disturbing layout
 * or comments.
 *
 * <p>The comment + key-order overlay is recovered by a TEXT pass ({@link #readComments}) that is
 * independent of the mapper's data pass ({@link #readTree}). Comment text is stored WITHOUT the
 * {@code #} prefix; the prefix is (re)added when emitting.
 */
public final class YamlCodec implements Codec, ObjectMapperAware, CommentAware {

    private static final char SEP = '.';

    /** One shared, isolated default mapper reused across every default-constructed instance. */
    private static final ObjectMapper DEFAULT = FCMapperProfiles.storageSafe(buildYamlMapper());

    /** Dumps single leaf values only; kept separate so structure layout never flows through it. */
    private final ObjectMapper mapper;

    public YamlCodec() {
        this.mapper = DEFAULT;
    }

    /** Uses an isolated copy of the user's mapper so a later external mutation cannot leak in. */
    public YamlCodec(final ObjectMapper userMapper) {
        this.mapper = FCMapperProfiles.isolate(userMapper, () -> DEFAULT);
    }

    private static YAMLMapper buildYamlMapper() {
        return YAMLMapper.builder()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER) // no leading '---'
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .disable(YAMLGenerator.Feature.SPLIT_LINES) // keep a long scalar on one line
                .build();
    }

    // ---- identity -------------------------------------------------------

    @Override
    public String formatId() {
        return "yaml";
    }

    @Override
    public String[] fileExtensions() {
        return new String[]{"yml", "yaml"};
    }

    @Override
    public CommentFidelity commentFidelity() {
        return CommentFidelity.LOSSLESS;
    }

    @Override
    public ObjectMapper objectMapper() {
        return mapper;
    }

    // ---- text <-> tree --------------------------------------------------

    @Override
    public JsonNode readTree(final String text) {
        try {
            return mapper.readTree(text);
        } catch (final Exception e) {
            throw new CodecException("failed to parse YAML", e);
        }
    }

    @Override
    public String writeTreePlain(final JsonNode tree) {
        try {
            return mapper.writeValueAsString(tree);
        } catch (final Exception e) {
            throw new CodecException("failed to write YAML", e);
        }
    }

    @Override
    public <V> V treeToValue(final JsonNode node, final JavaType type) {
        try {
            return mapper.convertValue(node, type);
        } catch (final Exception e) {
            throw new CodecException("failed to bind YAML node to " + type, e);
        }
    }

    @Override
    public JsonNode valueToTree(final Object value) {
        try {
            return mapper.valueToTree(value);
        } catch (final Exception e) {
            throw new CodecException("failed to project value to YAML tree", e);
        }
    }

    // ---- CommentAware ---------------------------------------------------

    @Override
    public CommentLoad readComments(final String text) {
        final CommentTree comments = parseComments(text);
        final JsonNode data = readTree(text);
        final KeyOrder order = (data instanceof ObjectNode)
                ? KeyOrder.capture((ObjectNode) data, SEP)
                : KeyOrder.empty();
        return new CommentLoad(comments, order);
    }

    @Override
    public String writeWithComments(final JsonNode tree, final CommentTree commentTree, final KeyOrder keyOrder) {
        if (!(tree instanceof ObjectNode)) {
            throw new CodecException("YAML document root must be an object");
        }
        final StringBuilder out = new StringBuilder();
        final CommentTree comments = commentTree != null ? commentTree : new CommentTree();
        final KeyOrder order = keyOrder != null ? keyOrder : KeyOrder.empty();
        emit((ObjectNode) tree, "", 0, out, comments, order);
        return out.toString();
    }

    @Override
    public String writeScalar(final Object leaf) {
        if (leaf instanceof JsonNode) {
            final JsonNode node = (JsonNode) leaf;
            if (node.isContainerNode() && node.size() > 0) {
                throw new CodecException(
                        "writeScalar received a populated container; the emitter must recurse into it");
            }
        }
        try {
            String s = mapper.writeValueAsString(leaf);
            // The YAMLMapper emits a trailing newline (and may emit a doc marker for some shapes);
            // strip both so the structure emitter owns placement.
            if (s.startsWith("---\n")) {
                s = s.substring(4);
            }
            while (s.endsWith("\n") || s.endsWith("\r")) {
                s = s.substring(0, s.length() - 1);
            }
            return s;
        } catch (final Exception e) {
            throw new CodecException("failed to dump leaf value", e);
        }
    }

    // ---- structure emitter ---------------------------------------------

    private void emit(final ObjectNode node, final String parentPath, final int indent,
                      final StringBuilder out, final CommentTree comments, final KeyOrder order) {
        final String ind = spaces(indent);
        for (final String key : orderedFieldNames(node, parentPath, order)) {
            final JsonNode val = node.get(key);
            final String path = parentPath.isEmpty() ? key : parentPath + SEP + key;

            final String block = comments.getComment(path, CommentType.BLOCK);
            if (block != null) {
                for (final String commentLine : block.split("\n", -1)) {
                    out.append(ind).append(prefixComment(commentLine)).append('\n');
                }
            }
            final String side = sideText(comments.getComment(path, CommentType.SIDE));

            if (val instanceof ObjectNode && val.size() > 0) {
                out.append(ind).append(key).append(':');
                if (side != null) {
                    out.append(side);
                }
                out.append('\n');
                emit((ObjectNode) val, path, indent + 2, out, comments, order);
            } else {
                final String dumped = writeScalar(val);
                if (dumped.indexOf('\n') >= 0) {
                    // A block value (a list, or a multi-line string): key on its own line, value
                    // re-indented beneath it by the structure emitter.
                    out.append(ind).append(key).append(':');
                    if (side != null) {
                        out.append(side);
                    }
                    out.append('\n');
                    for (final String valueLine : dumped.split("\n", -1)) {
                        if (!valueLine.isEmpty()) {
                            out.append(ind).append("  ").append(valueLine).append('\n');
                        }
                    }
                } else {
                    out.append(ind).append(key).append(": ").append(dumped);
                    if (side != null) {
                        out.append(side);
                    }
                    out.append('\n');
                }
            }
        }
    }

    /** Captured key order first (for keys still present), then any live keys not in the snapshot. */
    private List<String> orderedFieldNames(final ObjectNode node, final String parentPath, final KeyOrder order) {
        final List<String> result = new ArrayList<>();
        final Set<String> live = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(live::add);
        for (final String k : order.orderedKeys(parentPath)) {
            if (live.contains(k)) {
                result.add(k);
            }
        }
        for (final String k : live) {
            if (!result.contains(k)) {
                result.add(k);
            }
        }
        return result;
    }

    // ---- comment text parser (text pass, no mapper) --------------------

    private static final class Frame {
        final int indent;
        final String key;

        Frame(final int indent, final String key) {
            this.indent = indent;
            this.key = key;
        }
    }

    private CommentTree parseComments(final String yaml) {
        final CommentTree tree = new CommentTree();
        final Deque<Frame> stack = new ArrayDeque<>();
        final List<String> pendingBlock = new ArrayList<>();

        for (final String raw : yaml.split("\n", -1)) {
            final String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            final String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                pendingBlock.add("");
                continue;
            }
            if (trimmed.charAt(0) == '#') {
                pendingBlock.add(trimmed);
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                pendingBlock.clear(); // comments on list items are not tracked
                continue;
            }

            final int colon = keyColon(trimmed);
            if (colon < 0) {
                pendingBlock.clear();
                continue;
            }

            final int indent = leadingSpaces(line);
            final String key = unquote(trimmed.substring(0, colon).trim());

            while (!stack.isEmpty() && stack.peek().indent >= indent) {
                stack.pop();
            }
            final String path = pathOf(stack, key);
            stack.push(new Frame(indent, key));

            if (!pendingBlock.isEmpty()) {
                final String block = stripComments(joinTrim(pendingBlock));
                if (!block.isEmpty()) {
                    tree.putFileComment(path, block, CommentType.BLOCK);
                }
                pendingBlock.clear();
            }

            final String afterColon = trimmed.substring(colon + 1);
            final int hash = sideHash(afterColon);
            if (hash >= 0) {
                tree.putFileComment(path, stripComment(afterColon.substring(hash).trim()), CommentType.SIDE);
            }
        }
        return tree;
    }

    // ---- comment formatting helpers ------------------------------------

    /** Add a {@code #} prefix to a stored (prefix-less) block comment line. */
    private static String prefixComment(final String line) {
        if (line.isEmpty()) {
            return "#";
        }
        return "# " + line;
    }

    /** Render a stored (prefix-less) side comment as the trailing {@code " # ..."} segment. */
    private static String sideText(final String stored) {
        if (stored == null) {
            return null;
        }
        return " # " + stored;
    }

    /** Strip the leading {@code #} (and one following space) from a single comment line. */
    private static String stripComment(final String line) {
        String s = line;
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.startsWith(" ")) {
            s = s.substring(1);
        }
        return s;
    }

    /** Strip the {@code #} prefix from every line of a multi-line block. */
    private static String stripComments(final String block) {
        final String[] lines = block.split("\n", -1);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(stripComment(lines[i]));
        }
        return sb.toString();
    }

    // ---- low-level text helpers ----------------------------------------

    private static int leadingSpaces(final String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /** Index of the ':' separating a key from its value (end-of-line or followed by a space). */
    private static int keyColon(final String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ':' && (i == s.length() - 1 || s.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    /** First {@code '#'} starting a side comment (preceded by a space, not inside quotes), or -1. */
    private static int sideHash(final String after) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < after.length(); i++) {
            final char c = after.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble && (i == 0 || after.charAt(i - 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(final String key) {
        if (key.length() >= 2) {
            final char a = key.charAt(0);
            final char b = key.charAt(key.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return key.substring(1, key.length() - 1);
            }
        }
        return key;
    }

    private static String pathOf(final Deque<Frame> ancestors, final String key) {
        final StringBuilder sb = new StringBuilder();
        final Iterator<Frame> it = ancestors.descendingIterator(); // outermost -> innermost
        while (it.hasNext()) {
            sb.append(it.next().key).append(SEP);
        }
        return sb.append(key).toString();
    }

    /** Join lines with '\n', dropping leading and trailing blank lines. */
    private static String joinTrim(final List<String> lines) {
        int start = 0;
        int end = lines.size();
        while (start < end && lines.get(start).isEmpty()) {
            start++;
        }
        while (end > start && lines.get(end - 1).isEmpty()) {
            end--;
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static String spaces(final int n) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
