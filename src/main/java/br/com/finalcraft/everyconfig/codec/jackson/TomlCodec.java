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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * TOML codec ({@link CommentFidelity#LOSSLESS}, {@link CommentAware}). Data parsing and leaf-value
 * serialization go through a Jackson {@link TomlMapper}, but the document STRUCTURE — bare {@code
 * key = value} pairs, {@code [table.path]} headers for nested objects (a table's own scalars are emitted
 * before its sub-tables, as TOML requires), key order and comment lines — is rendered by this codec's
 * own emitter; the mapper never sees the whole tree.
 *
 * <p>TOML has no null type, so a {@code null} value is omitted on write. The comment + key-order overlay
 * is recovered by a TEXT pass
 * ({@link #readComments}); comment text is stored WITHOUT the {@code #} prefix and re-added when emitting.
 */
public final class TomlCodec implements Codec, CommentAware {


    /** One shared, isolated default mapper reused across every default-constructed instance. */
    private static final ObjectMapper DEFAULT = ECMapperProfiles.storageSafe(new TomlMapper());

    private final ObjectMapper mapper;
    private final CompactElementResolver compactResolver;

    public TomlCodec() {
        this.mapper = DEFAULT;
        this.compactResolver = AnnotationCompactElementResolver.INSTANCE;
    }

    public TomlCodec(final ObjectMapper userMapper) {
        this(userMapper, null);
    }

    /** As {@link #TomlCodec(ObjectMapper)}, plus a consumer {@link CompactElementResolver} consulted AHEAD of
     *  the annotation resolver when classifying a collection's element for its compact form. */
    public TomlCodec(final ObjectMapper userMapper, final CompactElementResolver compactResolver) {
        this.mapper = ECMapperProfiles.isolate(userMapper, () -> DEFAULT);
        this.compactResolver = CompactElementResolver.compose(compactResolver, AnnotationCompactElementResolver.INSTANCE);
    }

    // ---- identity -------------------------------------------------------

    @Override
    public String formatId() {
        return "toml";
    }

    @Override
    public String[] fileExtensions() {
        return new String[]{"toml"};
    }

    @Override
    public CommentFidelity commentFidelity() {
        return CommentFidelity.LOSSLESS;
    }

    @Override
    public CompactElementResolver compactElementResolver() {
        return compactResolver;
    }

    @Override
    public ObjectMapper getObjectMapper() {
        return mapper;
    }

    // ---- text <-> tree --------------------------------------------------

    @Override
    public JsonNode readTree(final String text) {
        try {
            return mapper.readTree(text);
        } catch (final Exception e) {
            throw new CodecException("failed to parse TOML", e);
        }
    }

    @Override
    public String writeTreePlain(final JsonNode tree) {
        try {
            return mapper.writeValueAsString(tree);
        } catch (final Exception e) {
            throw new CodecException("failed to write TOML", e);
        }
    }

    @Override
    public <V> V treeToValue(final JsonNode node, final JavaType type) {
        try {
            return mapper.convertValue(node, type);
        } catch (final Exception e) {
            throw new CodecException("failed to bind TOML node to " + type, e);
        }
    }

    @Override
    public JsonNode valueToTree(final Object value) {
        try {
            return mapper.valueToTree(value);
        } catch (final Exception e) {
            throw new CodecException("failed to project value to TOML tree", e);
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
            throw new CodecException("TOML document root must be an object");
        }
        final CommentTree comments = commentTree != null ? commentTree : new CommentTree();
        final KeyOrder order = keyOrder != null ? keyOrder : KeyOrder.empty();
        final StringBuilder out = new StringBuilder();

        final List<String> header = comments.getHeader();
        for (final String line : header) {
            out.append(prefixComment(line)).append('\n');
        }
        if (!header.isEmpty()) {
            out.append('\n'); // blank line separates the header from the first entry
        }

        emitTable((ObjectNode) tree, "", out, comments, order);

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
        final JsonNode node = (leaf instanceof JsonNode) ? (JsonNode) leaf : mapper.valueToTree(leaf);
        if (node.isObject() && node.size() > 0) {
            throw new CodecException(
                    "writeScalar received a populated object; the emitter must emit it as a [table]");
        }
        if (node.isIntegralNumber()) {
            return integerToken(node);
        }
        return dumpInline(node);
    }

    // ---- structure emitter ---------------------------------------------

    /**
     * Emits a table's body: its own scalar/array keys first (as {@code key = value}), then each sub-section
     * (a child object as a {@code [path]} table, or a list of non-empty objects as repeated
     * {@code [[path]]} array-of-tables) in captured order, depth-first. TOML requires bare key/value pairs
     * before sub-sections, so scalars are emitted first; object tables and array-of-tables share one
     * ordered pass so their relative file order is preserved. A {@code null} value is omitted (TOML has no
     * null).
     */
    private void emitTable(final ObjectNode node, final String path, final StringBuilder out,
                           final CommentTree comments, final KeyOrder order) {
        final List<String> scalars = new ArrayList<>();
        final List<String> subSections = new ArrayList<>();
        for (final String key : orderedFieldNames(node, path, order)) {
            final JsonNode v = node.get(key);
            if (v == null || v.isNull()) {
                continue;
            }
            if ((v.isObject() && v.size() > 0) || isArrayOfTables(v)) {
                subSections.add(key);
            } else {
                scalars.add(key);
            }
        }

        for (final String key : scalars) {
            final String p = DPath.joinSegment(path, key);
            emitLeadingComments(p, out, comments);
            out.append(keyToken(key)).append(" = ").append(writeScalar(node.get(key)));
            final String side = comments.getComment(p, CommentType.SIDE);
            if (side != null) {
                out.append(' ').append(prefixComment(side));
            }
            out.append('\n');
        }

        for (final String key : subSections) {
            final String p = DPath.joinSegment(path, key);
            final JsonNode v = node.get(key);
            emitLeadingComments(p, out, comments);
            if (v.isObject()) {
                out.append('[').append(tablePath(p)).append(']').append('\n');
                emitTable((ObjectNode) v, p, out, comments, order);
            } else {
                // A list of non-empty objects renders as the idiomatic repeated [[path]] form; each element
                // is a fresh table body under the same path.
                for (final JsonNode element : v) {
                    out.append("[[").append(tablePath(p)).append("]]").append('\n');
                    emitTable((ObjectNode) element, p, out, comments, order);
                }
            }
        }
    }

    /**
     * True when an array can be written as {@code [[path]]} array-of-tables: it must be non-empty and every
     * element a non-empty object. An empty array, or an element that is a scalar or an empty object, would
     * alias to an empty body on re-read (a bare {@code [[path]]} parses back as one empty object), so those
     * stay on the inline path instead.
     */
    private static boolean isArrayOfTables(final JsonNode v) {
        if (v == null || !v.isArray() || v.size() == 0) {
            return false;
        }
        for (final JsonNode element : v) {
            if (!element.isObject() || element.size() == 0) {
                return false;
            }
        }
        return true;
    }

    private void emitLeadingComments(final String path, final StringBuilder out, final CommentTree comments) {
        for (int b = comments.getBlankLinesBefore(path); b > 0; b--) {
            out.append('\n');
        }
        final String block = comments.getComment(path, CommentType.BLOCK);
        if (block != null) {
            for (final String commentLine : block.split("\n", -1)) {
                out.append(prefixComment(commentLine)).append('\n');
            }
        }
    }

    /** Captured key order first (for keys still present), then any live keys not in the snapshot. */
    private List<String> orderedFieldNames(final ObjectNode node, final String parentPath, final KeyOrder order) {
        final Set<String> live = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(live::add);
        // Captured order first, then any live keys not yet placed. A LinkedHashSet keeps membership O(1):
        // an ArrayList.contains here was O(n²) and dominated the save of a node with many keys.
        final LinkedHashSet<String> result = new LinkedHashSet<>(Math.max(16, live.size() * 2));
        for (final String k : order.orderedKeys(parentPath)) {
            if (live.contains(k)) {
                result.add(k);
            }
        }
        result.addAll(live);
        return new ArrayList<>(result);
    }

    /**
     * Renders an integer, guarding against a mapper limitation: the TOML reader mis-parses some large
     * integers (it drops high digits), so an integer whose written form does not read back identically is
     * emitted as a quoted string instead. A consumer still reads it as digits (the numeric getters accept
     * a number stored as a string), and small integers — the common case — are emitted normally.
     */
    private String integerToken(final JsonNode node) {
        final BigInteger value = node.bigIntegerValue();
        if (value.bitLength() <= 31) {
            return dumpInline(node); // fits an int comfortably; never affected
        }
        final String inline = dumpInline(node);
        try {
            final JsonNode back = mapper.readTree("v = " + inline).get("v");
            if (back != null && back.isIntegralNumber() && back.bigIntegerValue().equals(value)) {
                return inline; // the reader round-trips this magnitude faithfully
            }
        } catch (final Exception ignored) {
            // fall through to the string fallback
        }
        return dumpInline(mapper.getNodeFactory().textNode(value.toString()));
    }

    /**
     * Serialize a single leaf to its TOML inline form by wrapping it in a throwaway key and taking the
     * text after {@code =}. This routes scalars (and inline arrays) through the mapper for correct
     * quoting/number/array rendering without hand-writing TOML value syntax.
     */
    private String dumpInline(final JsonNode node) {
        try {
            final ObjectNode wrap = mapper.createObjectNode();
            wrap.set("v", node);
            final String s = mapper.writeValueAsString(wrap); // "v = <value>\n"
            final int eq = s.indexOf('=');
            return (eq >= 0 ? s.substring(eq + 1) : s).trim();
        } catch (final Exception e) {
            throw new CodecException("failed to dump TOML value", e);
        }
    }

    // ---- comment text parser (text pass, no mapper) --------------------

    private CommentTree parseComments(final String toml) {
        final CommentTree tree = new CommentTree();
        final List<String> pending = new ArrayList<>();
        String currentTable = "";
        boolean firstSeen = false;

        for (final String raw : toml.split("\n", -1)) {
            final String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            final String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                pending.add("");
                continue;
            }
            if (trimmed.charAt(0) == '#') {
                pending.add(trimmed);
                continue;
            }

            if (trimmed.charAt(0) == '[') {
                final boolean arrayTable = trimmed.startsWith("[[");
                final int close = trimmed.indexOf(arrayTable ? "]]" : "]");
                final String inner = trimmed.substring(arrayTable ? 2 : 1,
                        close < 0 ? trimmed.length() : close).trim();
                final String path = tablePathToInternal(inner);
                assignComments(tree, path, peelHeaderIfFirst(tree, pending, firstSeen));
                firstSeen = true;
                pending.clear();
                currentTable = path;
                continue;
            }

            final int eq = keyEquals(trimmed);
            if (eq < 0) {
                pending.clear();
                continue;
            }
            final String key = unquote(trimmed.substring(0, eq).trim());
            final String path = DPath.joinSegment(currentTable, key);
            assignComments(tree, path, peelHeaderIfFirst(tree, pending, firstSeen));
            firstSeen = true;
            pending.clear();

            final String value = trimmed.substring(eq + 1);
            final int hash = sideHash(value);
            if (hash >= 0) {
                tree.putFileComment(path, stripComment(value.substring(hash).trim()), CommentType.SIDE);
            }
        }

        final List<String> footer = extractBlockLines(pending);
        if (!footer.isEmpty()) {
            tree.setFooter(footer);
        }
        return tree;
    }

    /**
     * Before the first entry, the leading comment block may be a file header (a comment block followed by
     * a blank line) rather than the entry's own comment; peel it off and return the entry's own pending.
     */
    private List<String> peelHeaderIfFirst(final CommentTree tree, final List<String> pending, final boolean firstSeen) {
        if (firstSeen) {
            return pending;
        }
        final int boundary = headerBoundary(pending);
        if (boundary >= 0) {
            tree.setHeader(extractBlockLines(pending.subList(0, boundary)));
            return new ArrayList<>(pending.subList(boundary + 1, pending.size()));
        }
        return pending;
    }

    private void assignComments(final CommentTree tree, final String path, final List<String> pend) {
        int leadingBlanks = 0;
        while (leadingBlanks < pend.size() && pend.get(leadingBlanks).isEmpty()) {
            leadingBlanks++;
        }
        tree.setBlankLinesBefore(path, leadingBlanks);
        final List<String> block = extractBlockLines(pend);
        if (!block.isEmpty()) {
            tree.putFileComment(path, String.join("\n", block), CommentType.BLOCK);
        }
    }

    private static int headerBoundary(final List<String> pending) {
        return LineComments.headerBoundary(pending);
    }

    private static List<String> extractBlockLines(final List<String> raw) {
        return LineComments.extractBlockLines("#", raw);
    }

    // ---- token / path helpers ------------------------------------------

    /** A TOML bare key when it matches {@code [A-Za-z0-9_-]+}, otherwise a quoted key. */
    private String keyToken(final String key) {
        if (isBareKey(key)) {
            return key;
        }
        return dumpInline(mapper.getNodeFactory().textNode(key));
    }

    /** The escaped internal path (e.g. {@code database.pool}, or {@code a\.b} for a dotted key) rendered as
     *  a TOML dotted header with each LITERAL segment bare-or-quoted, e.g. {@code database.pool} or
     *  {@code "a.b"}. The escape and the TOML quoting are separate layers: a dot inside a key is unescaped
     *  here and the segment is then quoted by TOML. */
    private String tablePath(final String internalPath) {
        final String[] segments = DPath.split(internalPath); // literal segments (escapes resolved)
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(keyToken(segments[i]));
        }
        return sb.toString();
    }

    /** Parse a {@code [a.b."c.d"]} header's inner text back into the escaped internal path: each TOML
     *  segment (quoted or bare) becomes one literal key, re-escaped so a dot inside it stays part of it. */
    private static String tablePathToInternal(final String inner) {
        final List<String> segments = new ArrayList<>();
        final StringBuilder cur = new StringBuilder();
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < inner.length(); i++) {
            final char c = inner.charAt(i);
            if (inString) {
                if (c == quote) {
                    inString = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '.') {
                segments.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        segments.add(cur.toString().trim());
        String path = "";
        for (final String seg : segments) {
            path = DPath.joinSegment(path, seg);
        }
        return path;
    }

    private static boolean isBareKey(final String key) {
        if (key.isEmpty()) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            final char c = key.charAt(i);
            final boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Index of the {@code =} separating a key from its value (not inside a quoted key), or -1. */
    private static int keyEquals(final String s) {
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (inString) {
                if (c == quote) {
                    inString = false;
                }
            } else if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '=') {
                return i;
            }
        }
        return -1;
    }

    /** First {@code #} starting a side comment (preceded by a space, not inside a string), or -1. */
    private static int sideHash(final String after) {
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < after.length(); i++) {
            final char c = after.charAt(i);
            if (inString) {
                if (c == quote) {
                    inString = false;
                }
            } else if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '#' && (i == 0 || after.charAt(i - 1) == ' ')) {
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

    private static String prefixComment(final String line) {
        return LineComments.prefix("#", line);
    }

    private static String stripComment(final String line) {
        return LineComments.strip("#", line);
    }
}
