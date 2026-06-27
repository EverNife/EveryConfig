package br.com.finalcraft.everyconfig.codec.jackson;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CodecException;
import br.com.finalcraft.everyconfig.codec.CommentAware;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.FCMapperProfiles;
import br.com.finalcraft.everyconfig.codec.ObjectMapperAware;
import br.com.finalcraft.everyconfig.core.KeyOrder;
import br.com.finalcraft.everyconfig.core.comment.CommentTree;
import br.com.finalcraft.everyconfig.core.comment.CommentType;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JSON-with-comments codec ({@link CommentFidelity#LOSSY}, {@link CommentAware}). Data parsing and
 * leaf-value serialization go through a Jackson mapper that tolerates {@code //} comments and trailing
 * commas, but the document STRUCTURE (braces, indent, key order, comment lines) is rendered by this
 * codec's own emitter — the mapper never sees the whole tree. Comments are best-effort: block comments
 * above a key, a side comment after a value, plus a file header and footer round-trip; positions JSON
 * allows but the path-keyed overlay cannot address (between array elements, after a comma mid-value) are
 * not preserved.
 *
 * <p>The comment + key-order overlay is recovered by a TEXT pass ({@link #readComments}); comment text
 * is stored WITHOUT the {@code //} prefix and the prefix is (re)added when emitting.
 */
public final class JsoncCodec implements Codec, ObjectMapperAware, CommentAware {

    private static final char SEP = '.';

    /** One shared, isolated default mapper reused across every default-constructed instance. */
    private static final ObjectMapper DEFAULT = FCMapperProfiles.strictJson(buildJsoncMapper());

    private final ObjectMapper mapper;

    public JsoncCodec() {
        this.mapper = DEFAULT;
    }

    /** Uses an isolated copy of the user's mapper so a later external mutation cannot leak in. */
    public JsoncCodec(final ObjectMapper userMapper) {
        this.mapper = FCMapperProfiles.isolate(userMapper, () -> DEFAULT);
    }

    private static JsonMapper buildJsoncMapper() {
        return JsonMapper.builder()
                .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS) // read // and /* */
                .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .build();
    }

    // ---- identity -------------------------------------------------------

    @Override
    public String formatId() {
        return "jsonc";
    }

    @Override
    public String[] fileExtensions() {
        return new String[]{"jsonc"};
    }

    @Override
    public CommentFidelity commentFidelity() {
        return CommentFidelity.LOSSY;
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
            throw new CodecException("failed to parse JSONC", e);
        }
    }

    @Override
    public String writeTreePlain(final JsonNode tree) {
        try {
            return mapper.writeValueAsString(tree);
        } catch (final Exception e) {
            throw new CodecException("failed to write JSONC", e);
        }
    }

    @Override
    public <V> V treeToValue(final JsonNode node, final JavaType type) {
        try {
            return mapper.convertValue(node, type);
        } catch (final Exception e) {
            throw new CodecException("failed to bind JSONC node to " + type, e);
        }
    }

    @Override
    public JsonNode valueToTree(final Object value) {
        try {
            return mapper.valueToTree(value);
        } catch (final Exception e) {
            throw new CodecException("failed to project value to JSONC tree", e);
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
            throw new CodecException("JSONC document root must be an object");
        }
        final CommentTree comments = commentTree != null ? commentTree : new CommentTree();
        final KeyOrder order = keyOrder != null ? keyOrder : KeyOrder.empty();
        final StringBuilder out = new StringBuilder();

        final List<String> header = comments.getHeader();
        for (final String line : header) {
            out.append(prefixComment(line)).append('\n');
        }
        if (!header.isEmpty()) {
            out.append('\n'); // blank line separates the header from the opening brace
        }

        emitObject((ObjectNode) tree, "", 0, out, comments, order);
        out.append('\n');

        final List<String> footer = comments.getFooter();
        if (!footer.isEmpty()) {
            for (final String line : footer) {
                out.append(prefixComment(line)).append('\n');
            }
        }
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
        return dumpScalar(leaf);
    }

    // ---- structure emitter ---------------------------------------------

    /** Emits {@code { ... }} for an object (no trailing newline); the caller positions it after a key. */
    private void emitObject(final ObjectNode node, final String parentPath, final int indent,
                            final StringBuilder out, final CommentTree comments, final KeyOrder order) {
        final String ind = spaces(indent * 2);
        final String childInd = spaces((indent + 1) * 2);
        final List<String> keys = orderedFieldNames(node, parentPath, order);
        if (keys.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        for (int i = 0; i < keys.size(); i++) {
            final String key = keys.get(i);
            final boolean last = i == keys.size() - 1;
            final JsonNode val = node.get(key);
            final String path = parentPath.isEmpty() ? key : parentPath + SEP + key;

            for (int b = comments.getBlankLinesBefore(path); b > 0; b--) {
                out.append('\n');
            }
            final String block = comments.getComment(path, CommentType.BLOCK);
            if (block != null) {
                for (final String commentLine : block.split("\n", -1)) {
                    out.append(childInd).append(prefixComment(commentLine)).append('\n');
                }
            }

            out.append(childInd).append(dumpScalar(key)).append(": ");
            if (val instanceof ObjectNode && val.size() > 0) {
                emitObject((ObjectNode) val, path, indent + 1, out, comments, order);
            } else if (val instanceof ArrayNode && val.size() > 0) {
                emitArray((ArrayNode) val, childInd, out);
            } else {
                out.append(writeScalar(val));
            }
            if (!last) {
                out.append(',');
            }
            final String side = comments.getComment(path, CommentType.SIDE);
            if (side != null) {
                out.append(' ').append(prefixComment(side));
            }
            out.append('\n');
        }
        out.append(ind).append('}');
    }

    /** Renders an array via the mapper (no per-element comments) re-indented under its key. */
    private void emitArray(final ArrayNode arr, final String keyIndent, final StringBuilder out) {
        final String[] lines = dumpScalar(arr).split("\n", -1);
        out.append(lines[0]); // the opening '['
        for (int i = 1; i < lines.length; i++) {
            out.append('\n').append(keyIndent).append(lines[i]);
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

    /** Serialize a single value through the mapper, stripping trailing newlines so the emitter places it. */
    private String dumpScalar(final Object value) {
        try {
            String s = mapper.writeValueAsString(value);
            while (s.endsWith("\n") || s.endsWith("\r")) {
                s = s.substring(0, s.length() - 1);
            }
            return s;
        } catch (final Exception e) {
            throw new CodecException("failed to dump value", e);
        }
    }

    // ---- comment text parser (text pass, no mapper) --------------------

    private CommentTree parseComments(final String jsonc) {
        final CommentTree tree = new CommentTree();
        final Deque<String> stack = new ArrayDeque<>(); // keys of the currently-open objects
        final List<String> pending = new ArrayList<>();
        boolean rootSeen = false;
        int arrayDepth = 0;

        for (final String raw : jsonc.split("\n", -1)) {
            final String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            final String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                pending.add("");
                continue;
            }
            if (trimmed.startsWith("//")) {
                pending.add(trimmed);
                continue;
            }
            if (arrayDepth > 0) {
                arrayDepth += netBrackets(trimmed); // skip array interior; no per-element comments
                pending.clear();
                continue;
            }
            if (!rootSeen && trimmed.startsWith("{")) {
                rootSeen = true;
                final List<String> hdr = extractBlockLines(pending);
                if (!hdr.isEmpty()) {
                    tree.setHeader(hdr);
                }
                pending.clear();
                continue;
            }
            if (trimmed.charAt(0) == '}') {
                if (!stack.isEmpty()) {
                    stack.pop(); // a nested object closed
                }
                pending.clear();
                continue;
            }

            final int colon = keyColon(trimmed);
            if (colon < 0) {
                pending.clear();
                continue;
            }
            final String key = unquoteJson(trimmed.substring(0, colon).trim());
            final String path = pathOf(stack, key);

            int leadingBlanks = 0;
            while (leadingBlanks < pending.size() && pending.get(leadingBlanks).isEmpty()) {
                leadingBlanks++;
            }
            tree.setBlankLinesBefore(path, leadingBlanks);
            final List<String> blockLines = extractBlockLines(pending);
            if (!blockLines.isEmpty()) {
                tree.putFileComment(path, String.join("\n", blockLines), CommentType.BLOCK);
            }
            pending.clear();

            String value = trimmed.substring(colon + 1).trim();
            final int side = sideSlash(value);
            if (side >= 0) {
                tree.putFileComment(path, stripComment(value.substring(side).trim()), CommentType.SIDE);
                value = value.substring(0, side).trim();
            }
            if (value.endsWith(",")) {
                value = value.substring(0, value.length() - 1).trim();
            }

            if (value.endsWith("{")) {
                stack.push(key); // an object opens here
            } else {
                arrayDepth += netBrackets(value); // > 0 only when an array opens and stays open
            }
        }

        final List<String> footer = extractBlockLines(pending);
        if (!footer.isEmpty()) {
            tree.setFooter(footer);
        }
        return tree;
    }

    /** Drop leading/trailing blank lines, strip the {@code //} marker from each remaining line. */
    private static List<String> extractBlockLines(final List<String> raw) {
        int start = 0;
        int end = raw.size();
        while (start < end && raw.get(start).isEmpty()) {
            start++;
        }
        while (end > start && raw.get(end - 1).isEmpty()) {
            end--;
        }
        final List<String> out = new ArrayList<>();
        for (int i = start; i < end; i++) {
            out.add(stripComment(raw.get(i)));
        }
        return out;
    }

    // ---- comment formatting + low-level helpers ------------------------

    /** Add a {@code //} prefix to a stored (prefix-less) comment line. */
    private static String prefixComment(final String line) {
        if (line.isEmpty()) {
            return "//";
        }
        return "// " + line;
    }

    /** Strip the leading {@code //} (and one following space) from a single comment line. */
    private static String stripComment(final String line) {
        String s = line;
        if (s.startsWith("//")) {
            s = s.substring(2);
        }
        if (s.startsWith(" ")) {
            s = s.substring(1);
        }
        return s;
    }

    /** Index of the ':' that separates a leading {@code "key"} from its value, or -1. */
    private static int keyColon(final String trimmed) {
        if (trimmed.isEmpty() || trimmed.charAt(0) != '"') {
            return -1;
        }
        int i = 1;
        while (i < trimmed.length()) {
            final char c = trimmed.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                break;
            }
            i++;
        }
        if (i >= trimmed.length()) {
            return -1;
        }
        int j = i + 1;
        while (j < trimmed.length() && trimmed.charAt(j) == ' ') {
            j++;
        }
        return (j < trimmed.length() && trimmed.charAt(j) == ':') ? j : -1;
    }

    /** First {@code //} starting a side comment (preceded by a space, not inside a string), or -1. */
    private static int sideSlash(final String after) {
        boolean inString = false;
        for (int i = 0; i < after.length() - 1; i++) {
            final char c = after.charAt(i);
            if (c == '"' && (i == 0 || after.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString && c == '/' && after.charAt(i + 1) == '/'
                    && (i == 0 || after.charAt(i - 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    /** Net {@code [} minus {@code ]} outside of strings — tracks open array depth across lines. */
    private static int netBrackets(final String s) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString && c == '[') {
                depth++;
            } else if (!inString && c == ']') {
                depth--;
            }
        }
        return depth;
    }

    private static String unquoteJson(final String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }

    private static String pathOf(final Deque<String> ancestors, final String key) {
        final StringBuilder sb = new StringBuilder();
        final Iterator<String> it = ancestors.descendingIterator(); // outermost -> innermost
        while (it.hasNext()) {
            sb.append(it.next()).append(SEP);
        }
        return sb.append(key).toString();
    }

    private static String spaces(final int n) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
