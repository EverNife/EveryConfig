package br.com.finalcraft.everyconfig.codec.jackson;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CodecException;
import br.com.finalcraft.everyconfig.codec.CommentAware;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.codec.ECMapperProfiles;
import br.com.finalcraft.everyconfig.selfdescribe.AnnotationCompactElementResolver;
import br.com.finalcraft.everyconfig.selfdescribe.CompactElementResolver;
import br.com.finalcraft.everyconfig.core.KeyOrder;
import br.com.finalcraft.everyconfig.core.comment.CommentTree;
import br.com.finalcraft.everyconfig.core.comment.CommentType;
import br.com.finalcraft.everyconfig.core.tree.DPath;
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
public final class JsoncCodec implements Codec, CommentAware {


    /** One shared, isolated default mapper reused across every default-constructed instance. */
    private static final ObjectMapper DEFAULT = ECMapperProfiles.strictJson(buildJsoncMapper());

    private final ObjectMapper mapper;
    private final CompactElementResolver compactResolver;

    public JsoncCodec() {
        this.mapper = DEFAULT;
        this.compactResolver = AnnotationCompactElementResolver.INSTANCE;
    }

    /** Uses an isolated copy of the user's mapper so a later external mutation cannot leak in. */
    public JsoncCodec(final ObjectMapper userMapper) {
        this(userMapper, null);
    }

    /** As {@link #JsoncCodec(ObjectMapper)}, plus a consumer {@link CompactElementResolver} consulted AHEAD of
     *  the annotation resolver when classifying a collection's element for its compact form. */
    public JsoncCodec(final ObjectMapper userMapper, final CompactElementResolver compactResolver) {
        this.mapper = ECMapperProfiles.isolate(userMapper, () -> DEFAULT);
        this.compactResolver = CompactElementResolver.compose(compactResolver, AnnotationCompactElementResolver.INSTANCE);
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
    public ObjectMapper getObjectMapper() {
        return mapper;
    }

    @Override
    public CompactElementResolver compactElementResolver() {
        return compactResolver;
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
                ? KeyOrder.capture((ObjectNode) data)
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
            final String path = DPath.joinSegment(parentPath, key);

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
                emitArray((ArrayNode) val, path, childInd, out, comments);
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

    /**
     * Renders an array under its key. A scalar array carrying at least one per-element comment is rendered
     * multi-line — one element per line, each block comment above its element, addressed as
     * {@code path.i} — so the comment has an addressable home. Every other array (object elements, or no
     * element comment) keeps the inline whole-array dump, byte-identical to before.
     */
    private void emitArray(final ArrayNode arr, final String path, final String keyIndent,
                           final StringBuilder out, final CommentTree comments) {
        if (allValueNodes(arr) && anyElementComment(arr, path, comments)) {
            final String elemIndent = keyIndent + "  ";
            out.append("[\n");
            for (int i = 0; i < arr.size(); i++) {
                final String block = comments.getComment(DPath.joinSegment(path, String.valueOf(i)), CommentType.BLOCK);
                if (block != null) {
                    for (final String commentLine : block.split("\n", -1)) {
                        out.append(elemIndent).append(prefixComment(commentLine)).append('\n');
                    }
                }
                out.append(elemIndent).append(dumpScalar(arr.get(i)));
                if (i < arr.size() - 1) {
                    out.append(',');
                }
                out.append('\n');
            }
            out.append(keyIndent).append(']');
            return;
        }
        final String[] lines = dumpScalar(arr).split("\n", -1);
        out.append(lines[0]); // the opening '['
        for (int i = 1; i < lines.length; i++) {
            out.append('\n').append(keyIndent).append(lines[i]);
        }
    }

    /** True when every element is a scalar (the only kind that carries a tracked per-element comment). */
    private static boolean allValueNodes(final ArrayNode arr) {
        for (final JsonNode e : arr) {
            if (!e.isValueNode()) {
                return false;
            }
        }
        return true;
    }

    /** True when any element under {@code path} (as {@code path.i}) carries a block comment. */
    private static boolean anyElementComment(final ArrayNode arr, final String path, final CommentTree comments) {
        for (int i = 0; i < arr.size(); i++) {
            if (comments.getComment(DPath.joinSegment(path, String.valueOf(i)), CommentType.BLOCK) != null) {
                return true;
            }
        }
        return false;
    }

    /** The emit order for this node: captured order, then appended keys, re-partitioned by pin zone —
     *  centralized in {@link KeyOrder#arrange}. */
    private List<String> orderedFieldNames(final ObjectNode node, final String parentPath, final KeyOrder order) {
        final List<String> live = new ArrayList<>();
        node.fieldNames().forEachRemaining(live::add);
        return order.arrange(parentPath, live);
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
        String arrayPath = null;     // the scalar array currently being read, for element indexing
        int arrayElementIndex = 0;

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
                final int before = arrayDepth;
                arrayDepth += netBrackets(trimmed);
                // At the array's own depth, attach a pending block comment to a SCALAR element (path.i);
                // object/nested elements keep the whole-array path and carry no per-element comment.
                if (before == 1 && arrayPath != null && isScalarElementLine(trimmed)) {
                    assignElementComment(tree, DPath.joinSegment(arrayPath, String.valueOf(arrayElementIndex)), pending);
                    arrayElementIndex++;
                }
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
                final int delta = netBrackets(value); // > 0 only when an array opens and stays open
                arrayDepth += delta;
                if (delta > 0) {
                    arrayPath = path; // a multi-line array opens; index its scalar elements
                    arrayElementIndex = 0;
                }
            }
        }

        final List<String> footer = extractBlockLines(pending);
        if (!footer.isEmpty()) {
            tree.setFooter(footer);
        }
        return tree;
    }

    /** True for an array-interior line that is a scalar element (not a comment, brace/bracket, or a
     *  {@code "key":} object-body line). */
    private static boolean isScalarElementLine(final String trimmed) {
        if (trimmed.isEmpty() || trimmed.startsWith("//")) {
            return false;
        }
        final char c = trimmed.charAt(0);
        if (c == '{' || c == '[' || c == '}' || c == ']') {
            return false;
        }
        return keyColon(trimmed) < 0; // a scalar element has no leading "key": separator
    }

    /** Attach the drained {@code pending} lines as {@code path}'s block comment (no blank-line tracking). */
    private void assignElementComment(final CommentTree tree, final String path, final List<String> pending) {
        final List<String> blockLines = extractBlockLines(pending);
        if (!blockLines.isEmpty()) {
            tree.putFileComment(path, String.join("\n", blockLines), CommentType.BLOCK);
        }
    }

    /** Drop leading/trailing blank lines, strip the {@code //} marker from each remaining line. */
    private static List<String> extractBlockLines(final List<String> raw) {
        return LineComments.extractBlockLines("//", raw);
    }

    // ---- comment formatting + low-level helpers ------------------------

    /** Add a {@code //} prefix to a stored (prefix-less) comment line. */
    private static String prefixComment(final String line) {
        return LineComments.prefix("//", line);
    }

    /** Strip the leading {@code //} (and one following space) from a single comment line. */
    private static String stripComment(final String line) {
        return LineComments.strip("//", line);
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
            sb.append(DPath.escapeSegment(it.next())).append(DPath.SEP);
        }
        return sb.append(DPath.escapeSegment(key)).toString();
    }

    private static String spaces(final int n) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
