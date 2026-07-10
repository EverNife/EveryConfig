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
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.yaml.snakeyaml.LoaderOptions;

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
public final class YamlCodec implements Codec, CommentAware {


    /** One shared, isolated default mapper reused across every default-constructed instance. */
    private static final ObjectMapper DEFAULT = ECMapperProfiles.storageSafe(buildYamlMapper());

    /** Dumps single leaf values only; kept separate so structure layout never flows through it. */
    private final ObjectMapper mapper;
    private final CompactElementResolver compactResolver;

    public YamlCodec() {
        this.mapper = DEFAULT;
        this.compactResolver = AnnotationCompactElementResolver.INSTANCE;
    }

    /** Uses an isolated copy of the user's mapper so a later external mutation cannot leak in. */
    public YamlCodec(final ObjectMapper userMapper) {
        this(userMapper, null);
    }

    /** As {@link #YamlCodec(ObjectMapper)}, plus a consumer {@link CompactElementResolver} consulted AHEAD of
     *  the annotation resolver when classifying a collection's element for its compact form. */
    public YamlCodec(final ObjectMapper userMapper, final CompactElementResolver compactResolver) {
        this.mapper = ECMapperProfiles.isolate(userMapper, () -> DEFAULT);
        this.compactResolver = CompactElementResolver.compose(compactResolver, AnnotationCompactElementResolver.INSTANCE);
    }

    /** SnakeYAML's default input cap is 3 MB; a large config (tens of thousands of keys) exceeds it and the
     *  load fails. Config files are trusted local data, so the cap is raised to a generous bound. */
    private static final int YAML_CODE_POINT_LIMIT = 128 * 1024 * 1024; // 128 MB

    private static YAMLMapper buildYamlMapper() {
        final LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(YAML_CODE_POINT_LIMIT);
        final YAMLFactory factory = YAMLFactory.builder()
                .loaderOptions(loaderOptions)
                .build();
        return YAMLMapper.builder(factory)
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
                ? KeyOrder.capture((ObjectNode) data)
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

        final List<String> header = comments.getHeader();
        if (!header.isEmpty()) {
            for (final String line : header) {
                out.append(prefixComment(line)).append('\n');
            }
            out.append('\n'); // blank line separates the header from the first key
        }

        emit((ObjectNode) tree, "", 0, out, comments, order);

        final List<String> footer = comments.getFooter();
        if (!footer.isEmpty()) {
            out.append('\n');
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
            if (node.isObject() && node.size() > 0) {
                throw new CodecException(
                        "writeScalar received a populated object; the emitter must recurse into it");
            }
        }
        return dumpValue(leaf);
    }

    /**
     * Serialize a value through the mapper and strip the leading document marker and trailing newlines,
     * so the structure emitter alone controls placement. Used for scalars and for sequences (whose
     * elements carry no tracked comments and so are rendered whole, then re-indented under their key).
     */
    private String dumpValue(final Object value) {
        try {
            String s = mapper.writeValueAsString(value);
            // Strip the document-start marker the dumper may prepend. It is "---\n" normally, but for a
            // value that forces an explicit marker (e.g. an empty string -> "--- \"\"") it is "--- ".
            if (s.startsWith("---\n") || s.startsWith("--- ")) {
                s = s.substring(4);
            }
            while (s.endsWith("\n") || s.endsWith("\r")) {
                s = s.substring(0, s.length() - 1);
            }
            return s;
        } catch (final Exception e) {
            throw new CodecException("failed to dump value", e);
        }
    }

    // ---- structure emitter ---------------------------------------------

    private void emit(final ObjectNode node, final String parentPath, final int indent,
                      final StringBuilder out, final CommentTree comments, final KeyOrder order) {
        final String ind = spaces(indent);
        for (final String key : orderedFieldNames(node, parentPath, order)) {
            final JsonNode val = node.get(key);
            final String path = DPath.joinSegment(parentPath, key);

            for (int b = comments.getBlankLinesBefore(path); b > 0; b--) {
                out.append('\n'); // preserve the file's vertical spacing above this key
            }

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
            } else if (val instanceof ArrayNode && val.size() > 0) {
                // A sequence: render the key, then the elements re-indented beneath it.
                out.append(ind).append(key).append(':');
                if (side != null) {
                    out.append(side);
                }
                out.append('\n');
                final ArrayNode arr = (ArrayNode) val;
                if (allValueNodes(arr) && anyElementComment(arr, path, comments)) {
                    // A scalar sequence with at least one per-element comment: render item by item so each
                    // comment sits above its element. (Object/nested or uncommented sequences render whole
                    // below, byte-identical to before.)
                    for (int i = 0; i < arr.size(); i++) {
                        final String elemBlock = comments.getComment(DPath.joinSegment(path, String.valueOf(i)), CommentType.BLOCK);
                        if (elemBlock != null) {
                            for (final String commentLine : elemBlock.split("\n", -1)) {
                                out.append(ind).append("  ").append(prefixComment(commentLine)).append('\n');
                            }
                        }
                        out.append(ind).append("  ").append("- ").append(dumpValue(arr.get(i))).append('\n');
                    }
                } else {
                    for (final String valueLine : dumpValue(val).split("\n", -1)) {
                        if (!valueLine.isEmpty()) {
                            out.append(ind).append("  ").append(valueLine).append('\n');
                        }
                    }
                }
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
    private boolean anyElementComment(final ArrayNode arr, final String path, final CommentTree comments) {
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
        final Set<String> live = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(live::add);
        return order.arrange(parentPath, live);
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
        final List<String> pending = new ArrayList<>(); // comment lines + "" blanks since the last key
        boolean firstKeySeen = false;
        String currentArrayPath = null; // the scalar sequence currently being read, for element indexing
        int arrayElementIndex = 0;
        int blockScalarKeyIndent = -1;  // >= 0 while inside a literal/folded block scalar (its key's indent)
        int flowDepth = 0;              // > 0 while inside an open flow collection ([ ]/{ }) across lines
        String flowOwnerPath = null;    // the key a multi-line flow belongs to (for its closing-line comment)

        for (final String raw : yaml.split("\n", -1)) {
            final String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            final String trimmed = line.trim();

            // Inside a block scalar (| or >), every more-indented line (and any blank line) is literal text:
            // a '#' or a 'key:' there is content, not a comment or a key. The block ends at the first
            // non-blank line indented no deeper than the block's key.
            if (blockScalarKeyIndent >= 0) {
                if (trimmed.isEmpty() || leadingSpaces(line) > blockScalarKeyIndent) {
                    continue;
                }
                blockScalarKeyIndent = -1; // dedented: the block ended; fall through to process this line
            }

            // Inside a multi-line flow collection ([ ]/{ }), the interior lines are not keys. When it closes,
            // a trailing comment on the closing line belongs to the key that opened the flow.
            if (flowDepth > 0) {
                flowDepth += netBracketsYaml(trimmed);
                if (flowDepth <= 0) {
                    flowDepth = 0;
                    final int closeHash = sideHash(trimmed);
                    if (closeHash >= 0 && flowOwnerPath != null) {
                        tree.putFileComment(flowOwnerPath, stripComment(trimmed.substring(closeHash).trim()),
                                CommentType.SIDE);
                    }
                    flowOwnerPath = null;
                }
                pending.clear();
                continue;
            }

            if (trimmed.isEmpty()) {
                pending.add("");
                continue;
            }
            if (trimmed.charAt(0) == '#') {
                pending.add(trimmed);
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                final String afterDash = trimmed.equals("-") ? "" : trimmed.substring(2).trim();
                // Track a per-element block comment only for a SCALAR item; an object item ("- key: value")
                // is out of scope and handled as before, so the key stack is never disturbed.
                if (!afterDash.isEmpty() && keyColon(afterDash) < 0 && !stack.isEmpty()) {
                    final String arrayPath = pathOfStackTop(stack);
                    if (!arrayPath.equals(currentArrayPath)) {
                        currentArrayPath = arrayPath;
                        arrayElementIndex = 0;
                    }
                    assignBlockComment(tree, DPath.joinSegment(arrayPath, String.valueOf(arrayElementIndex)), pending);
                    arrayElementIndex++;
                }
                pending.clear();
                continue;
            }

            final int colon = keyColon(trimmed);
            if (colon < 0) {
                // A bare block-scalar header on its own line (the emitter's multi-line form: "key:" then an
                // indented "|") opens a block whose following, more-indented lines are literal text.
                if (isBlockScalarIndicator(trimmed)) {
                    blockScalarKeyIndent = leadingSpaces(line);
                }
                pending.clear();
                continue;
            }

            final int indent = leadingSpaces(line);
            final String key = unquote(trimmed.substring(0, colon).trim());

            while (!stack.isEmpty() && stack.peek().indent >= indent) {
                stack.pop();
            }
            final String path = pathOf(stack, key);
            stack.push(new Frame(indent, key));
            currentArrayPath = null; // a key ends any scalar-sequence element run

            // The block above the very first key may be a file header (a comment block separated from
            // the key by a blank line) rather than the key's own comment; peel it off if so.
            List<String> keyPending = pending;
            if (!firstKeySeen) {
                firstKeySeen = true;
                final int boundary = headerBoundary(pending);
                if (boundary >= 0) {
                    tree.setHeader(extractBlockLines(pending.subList(0, boundary)));
                    keyPending = new ArrayList<>(pending.subList(boundary + 1, pending.size()));
                }
            }

            int leadingBlanks = 0;
            while (leadingBlanks < keyPending.size() && keyPending.get(leadingBlanks).isEmpty()) {
                leadingBlanks++;
            }
            tree.setBlankLinesBefore(path, leadingBlanks);
            final List<String> blockLines = extractBlockLines(keyPending);
            if (!blockLines.isEmpty()) {
                tree.putFileComment(path, String.join("\n", blockLines), CommentType.BLOCK);
            }
            pending.clear();

            final String afterColon = trimmed.substring(colon + 1);
            final int hash = sideHash(afterColon);
            if (hash >= 0) {
                tree.putFileComment(path, stripComment(afterColon.substring(hash).trim()), CommentType.SIDE);
            }

            // A value that opens a block scalar (| / >) or a multi-line flow collection switches the parser
            // into the matching skip-mode, so the lines that follow are not mis-read as keys or comments.
            final String valuePart = (hash >= 0) ? afterColon.substring(0, hash) : afterColon;
            if (isBlockScalarIndicator(valuePart.trim())) {
                blockScalarKeyIndent = indent;
            } else {
                final int net = netBracketsYaml(valuePart);
                if (net > 0) {
                    flowDepth = net;
                    flowOwnerPath = path;
                }
            }
        }

        // Comment lines trailing the last key (no following key) are the file footer.
        final List<String> footer = extractBlockLines(pending);
        if (!footer.isEmpty()) {
            tree.setFooter(footer);
        }
        return tree;
    }

    /** The dotted path of the innermost frame (the key whose value the current sequence belongs to). */
    private static String pathOfStackTop(final Deque<Frame> stack) {
        final StringBuilder sb = new StringBuilder();
        final Iterator<Frame> it = stack.descendingIterator(); // outermost -> innermost
        while (it.hasNext()) {
            if (sb.length() > 0) {
                sb.append(DPath.SEP);
            }
            sb.append(DPath.escapeSegment(it.next().key));
        }
        return sb.toString();
    }

    /** Assign the drained {@code pending} lines as {@code path}'s block comment + blank-lines-before. */
    private void assignBlockComment(final CommentTree tree, final String path, final List<String> pendingLines) {
        int leadingBlanks = 0;
        while (leadingBlanks < pendingLines.size() && pendingLines.get(leadingBlanks).isEmpty()) {
            leadingBlanks++;
        }
        tree.setBlankLinesBefore(path, leadingBlanks);
        final List<String> blockLines = extractBlockLines(pendingLines);
        if (!blockLines.isEmpty()) {
            tree.putFileComment(path, String.join("\n", blockLines), CommentType.BLOCK);
        }
    }

    /** Index of the blank line separating the file header from the first key's block, or -1 (shared helper). */
    private static int headerBoundary(final List<String> pending) {
        return LineComments.headerBoundary(pending);
    }

    /** Drop leading/trailing blank lines, strip the {@code #} marker from each remaining line. */
    private static List<String> extractBlockLines(final List<String> raw) {
        return LineComments.extractBlockLines("#", raw);
    }

    // ---- comment formatting helpers ------------------------------------

    /** Add a {@code #} prefix to a stored (prefix-less) comment line (shared line-comment logic). */
    private static String prefixComment(final String line) {
        return LineComments.prefix("#", line);
    }

    /** Render a stored (prefix-less) side comment as the trailing {@code " # ..."} segment; trailing
     *  whitespace is dropped (via the shared prefixer), like every emitted comment. */
    private static String sideText(final String stored) {
        return stored == null ? null : " " + prefixComment(stored);
    }

    /** Strip the leading {@code #} (and one following space) from a single comment line. */
    private static String stripComment(final String line) {
        return LineComments.strip("#", line);
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

    /** True when {@code s} is a YAML block-scalar header by itself ({@code |} or {@code >} plus optional
     *  chomping/indent indicators), so the lines that follow it are literal text, not keys or comments. */
    private static boolean isBlockScalarIndicator(final String s) {
        if (s.isEmpty()) {
            return false;
        }
        final char first = s.charAt(0);
        if (first != '|' && first != '>') {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c != '-' && c != '+' && !Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    /** Net opening minus closing flow brackets ({@code [ { } ]}) outside quotes — tracks an open flow
     *  collection across lines (a wrapped flow map/sequence stays open until the brackets balance). */
    private static int netBracketsYaml(final String s) {
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble) {
                if (c == '[' || c == '{') {
                    depth++;
                } else if (c == ']' || c == '}') {
                    depth--;
                }
            }
        }
        return depth;
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
            sb.append(DPath.escapeSegment(it.next().key)).append(DPath.SEP);
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
