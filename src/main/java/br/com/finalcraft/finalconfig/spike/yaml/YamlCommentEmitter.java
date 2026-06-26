package br.com.finalcraft.finalconfig.spike.yaml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.util.Iterator;
import java.util.Map;

/**
 * SPIKE: the structure-rendering YAML emitter (decision #5). It walks the canonical {@code ObjectNode}
 * and renders STRUCTURE itself — keys, indentation, sections, and the {@link CommentTree} comments —
 * delegating ONLY leaf-value serialization (scalars, arrays) to a Jackson {@link YAMLMapper}. The
 * mapper output is never re-parsed, so a user-supplied mapper can't break the layout.
 *
 * <p>Mirrors jshepherd's {@code YamlPersistenceDelegate} tree-walking writer, but driven by an
 * {@code ObjectNode} + a {@code CommentTree} instead of reflected POJO fields. Promoted/refined in
 * phase 03 / 04.
 */
public final class YamlCommentEmitter {

    private final YAMLMapper valueMapper = YAMLMapper.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER) // no leading '---'
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build();

    private final CommentTree comments;

    public YamlCommentEmitter(final CommentTree comments) {
        this.comments = comments;
    }

    public String emit(final ObjectNode root) {
        final StringBuilder out = new StringBuilder();
        emit(root, "", 0, out);
        return out.toString();
    }

    private void emit(final ObjectNode node, final String parentPath, final int indent, final StringBuilder out) {
        final String ind = spaces(indent);
        final Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            final Map.Entry<String, JsonNode> e = it.next();
            final String key = e.getKey();
            final JsonNode val = e.getValue();
            final String path = parentPath.isEmpty() ? key : parentPath + "." + key;

            final String block = comments.block(path);
            if (block != null) {
                for (final String commentLine : block.split("\n", -1)) {
                    out.append(ind).append(commentLine).append('\n');
                }
            }
            final String side = comments.side(path);

            if (val instanceof ObjectNode && val.size() > 0) {
                out.append(ind).append(key).append(':');
                if (side != null) {
                    out.append(side);
                }
                out.append('\n');
                emit((ObjectNode) val, path, indent + 2, out);
            } else {
                final String dumped = dumpValue(val);
                if (dumped.indexOf('\n') >= 0) {
                    // Block value (list / multi-line): key on its own line, value indented under it.
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

    /** Serialize a single leaf value via Jackson and strip the trailing newline(s). */
    private String dumpValue(final JsonNode value) {
        try {
            String s = valueMapper.writeValueAsString(value);
            while (s.endsWith("\n") || s.endsWith("\r")) {
                s = s.substring(0, s.length() - 1);
            }
            return s;
        } catch (final Exception ex) {
            throw new RuntimeException("Failed to dump leaf value", ex);
        }
    }

    private static String spaces(final int n) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
