package br.com.finalcraft.everyconfig.config.section;

import br.com.finalcraft.everyconfig.binding.BindResult;
import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.MigrationResult;
import br.com.finalcraft.everyconfig.core.comment.CommentType;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A scoped view of a {@link Config} rooted at a path: a cursor over the owning config's single tree,
 * not a copy. Holds only {@code (config, path)} and delegates every call to the owning {@code Config}
 * with the sub-path prefixed. Non-snapshotting: reads are live, so thread-safety is the owning
 * {@code Config}'s contract.
 *
 * <p>Every accessor comes in two shapes: a <em>sub-path</em> form that prefixes {@code sub} under this
 * section (the mirror of the same {@link Config} method), and a <em>self</em> form that operates on THIS
 * section's own path. The self reads key on a {@link Class} or take no argument, so they never collide with
 * the sub-path forms; the self writes ({@link #setValue(Object)}, {@link #mergeValue(Object)},
 * {@link #setComment(String)}) take a single argument and target the section itself — pass a sub-path to
 * reach a child.
 */
public class ConfigSection {

    private final Config config;
    private final String path;

    public ConfigSection(final Config config, final String path) {
        this.config = config;
        this.path = path == null ? "" : path;
    }

    public Config getConfig() {
        return config;
    }

    public String getPath() {
        return path;
    }

    /** The leaf key of this section's path (its last segment). */
    public String getSectionKey() {
        final char sep = config.pathSeparator();
        final int i = path.lastIndexOf(sep);
        return i < 0 ? path : path.substring(i + 1);
    }

    public ConfigSection getParentSection() {
        final char sep = config.pathSeparator();
        final int i = path.lastIndexOf(sep);
        return new ConfigSection(config, i < 0 ? "" : path.substring(0, i));
    }

    /** Absolute path for a sub-path within this section. */
    public String concatSubPath(final String sub) {
        return config.concat(path, sub);
    }

    public ConfigSection getConfigSection(final String sub) {
        return new ConfigSection(config, concatSubPath(sub));
    }

    public ConfigSection getRootSection() {
        return new ConfigSection(config, "");
    }

    // ---- self: read / write THIS section's own node ----

    /** Whether this section's own path exists in the config (the self counterpart of {@link #contains}). */
    public boolean exists() {
        return config.contains(path);
    }

    /** This section's own value as a raw object; null when absent. */
    public Object getValue() {
        return config.getValue(path);
    }

    /** Bind this section's own node to {@code type} (a scalar or a POJO) — the typed self read, replacing the
     *  {@code getConfig().getValue(getPath(), type)} detour. */
    public <T> T getValue(final Class<T> type) {
        return config.getValue(path, type);
    }

    /** As {@link #getValue(Class)}, binding through an explicitly supplied {@code codec}. */
    public <T> T getValue(final Class<T> type, final Codec codec) {
        return config.getValue(path, type, codec);
    }

    /** Bind this section's own node ONTO {@code target} and return it — the in-place self counterpart of
     *  {@link #getValue(Class)}. */
    public <T> T getValueInto(final T target) {
        return config.getValueInto(path, target);
    }

    /** This section's own node bound to a list of {@code elementType} (empty, never null, when absent). */
    public <T> List<T> getList(final Class<T> elementType) {
        return config.getList(path, elementType);
    }

    /** As {@link #getList(Class)}, binding through an explicitly supplied {@code codec}. */
    public <T> List<T> getList(final Class<T> elementType, final Codec codec) {
        return config.getList(path, elementType, codec);
    }

    /** As {@link #getList(Class)}, also returning the {@link BindResult#issues()} collected. */
    public <T> BindResult<List<T>> getListResult(final Class<T> elementType) {
        return config.getListResult(path, elementType);
    }

    /** This section's own raw Jackson node; null when absent. */
    public JsonNode getNode() {
        return config.getNode(path);
    }

    public String getString() {
        return config.getString(path);
    }

    public int getInt() {
        return config.getInt(path);
    }

    public long getLong() {
        return config.getLong(path);
    }

    public double getDouble() {
        return config.getDouble(path);
    }

    public boolean getBoolean() {
        return config.getBoolean(path);
    }

    public UUID getUUID() {
        return config.getUUID(path);
    }

    public List<String> getStringList() {
        return config.getStringList(path);
    }

    /**
     * Writes {@code value} to THIS section's own path, <em>replacing</em> it (a POJO overrides the subtree,
     * like {@link Config#setValue(String, Object)}). The single-argument self counterpart of
     * {@link #setValue(String, Object)}: it targets the section ITSELF, so {@code section.setValue("x")} sets
     * this whole section to {@code "x"} — to set a child, pass its sub-path ({@code setValue("child", "x")}).
     */
    public void setValue(final Object value) {
        config.setValue(path, value);
    }

    /** Merge counterpart of {@link #setValue(Object)}: merges {@code value} INTO this section (unknown keys
     *  survive, the tree wins). Targets the section itself — pass a sub-path to merge into a child. */
    public void mergeValue(final Object value) {
        config.mergeValue(path, value);
    }

    /** This section's own block comment; null when none. */
    public String getComment() {
        return config.getComment(path);
    }

    /** Sets this section's own block comment, overwriting any existing one. Targets the section itself — pass
     *  a sub-path to comment a child ({@link #setComment(String, String)}). */
    public void setComment(final String comment) {
        config.setComment(path, comment);
    }

    // ---- containment / keys ----

    public boolean contains(final String sub) {
        return config.contains(concatSubPath(sub));
    }

    public Set<String> getKeys() {
        return config.getKeys(path);
    }

    public Set<String> getKeys(final String sub) {
        return config.getKeys(concatSubPath(sub));
    }

    public Set<String> getKeys(final String sub, final boolean deep) {
        return config.getKeys(concatSubPath(sub), deep);
    }

    public Set<ConfigSection> getKeysSections() {
        return config.getKeysSections(path);
    }

    public Set<ConfigSection> getKeysSections(final String sub) {
        return config.getKeysSections(concatSubPath(sub));
    }

    // ---- get / set / remove ----

    public Object getValue(final String sub) {
        return config.getValue(concatSubPath(sub));
    }

    /** Bind the node at {@code sub} to {@code type} — the sub-path mirror of {@link #getValue(Class)}. */
    public <T> T getValue(final String sub, final Class<T> type) {
        return config.getValue(concatSubPath(sub), type);
    }

    /** As {@link #getValue(String, Class)}, binding through an explicitly supplied {@code codec}. */
    public <T> T getValue(final String sub, final Class<T> type, final Codec codec) {
        return config.getValue(concatSubPath(sub), type, codec);
    }

    /** Bind the node at {@code sub} ONTO {@code target} and return it. */
    public <T> T getValueInto(final String sub, final T target) {
        return config.getValueInto(concatSubPath(sub), target);
    }

    /** The raw Jackson node at {@code sub}; null when absent. */
    public JsonNode getNode(final String sub) {
        return config.getNode(concatSubPath(sub));
    }

    public void setValue(final String sub, final Object value) {
        config.setValue(concatSubPath(sub), value);
    }

    public void setValue(final String sub, final Object value, final String comment) {
        config.setValue(concatSubPath(sub), value, comment);
    }

    /** Merge counterpart of {@link #setValue(String, Object)} (a POJO merges; unknown keys survive). */
    public void mergeValue(final String sub, final Object value) {
        config.mergeValue(concatSubPath(sub), value);
    }

    public void mergeValue(final String sub, final Object value, final String comment) {
        config.mergeValue(concatSubPath(sub), value, comment);
    }

    public boolean removeValue(final String sub) {
        return config.removeValue(concatSubPath(sub));
    }

    /** Removes this whole section (its subtree) from the config. */
    public boolean clear() {
        return config.removeValue(path);
    }

    /** Move a key (and its comment subtree) from {@code oldSub} to {@code newSub}, both relative to this
     *  section — the sub-path mirror of {@link Config#migrateKey(String, String)}. */
    public MigrationResult migrateKey(final String oldSub, final String newSub) {
        return config.migrateKey(concatSubPath(oldSub), concatSubPath(newSub));
    }

    public String getString(final String sub) {
        return config.getString(concatSubPath(sub));
    }

    public String getString(final String sub, final String def) {
        return config.getString(concatSubPath(sub), def);
    }

    public int getInt(final String sub) {
        return config.getInt(concatSubPath(sub));
    }

    public int getInt(final String sub, final int def) {
        return config.getInt(concatSubPath(sub), def);
    }

    public long getLong(final String sub) {
        return config.getLong(concatSubPath(sub));
    }

    public long getLong(final String sub, final long def) {
        return config.getLong(concatSubPath(sub), def);
    }

    public double getDouble(final String sub) {
        return config.getDouble(concatSubPath(sub));
    }

    public double getDouble(final String sub, final double def) {
        return config.getDouble(concatSubPath(sub), def);
    }

    public boolean getBoolean(final String sub) {
        return config.getBoolean(concatSubPath(sub));
    }

    public boolean getBoolean(final String sub, final boolean def) {
        return config.getBoolean(concatSubPath(sub), def);
    }

    public List<String> getStringList(final String sub) {
        return config.getStringList(concatSubPath(sub));
    }

    public List<String> getStringList(final String sub, final List<String> def) {
        return config.getStringList(concatSubPath(sub), def);
    }

    public <T> List<T> getList(final String sub, final Class<T> elementType) {
        return config.getList(concatSubPath(sub), elementType);
    }

    /** As {@link #getList(String, Class)}, binding through an explicitly supplied {@code codec}. */
    public <T> List<T> getList(final String sub, final Class<T> elementType, final Codec codec) {
        return config.getList(concatSubPath(sub), elementType, codec);
    }

    /** As {@link #getList(String, Class)}, also returning the {@link BindResult#issues()} collected. */
    public <T> BindResult<List<T>> getListResult(final String sub, final Class<T> elementType) {
        return config.getListResult(concatSubPath(sub), elementType);
    }

    /** As {@link #getListResult(String, Class)}, binding through an explicitly supplied {@code codec}. */
    public <T> BindResult<List<T>> getListResult(final String sub, final Class<T> elementType, final Codec codec) {
        return config.getListResult(concatSubPath(sub), elementType, codec);
    }

    public UUID getUUID(final String sub) {
        return config.getUUID(concatSubPath(sub));
    }

    public UUID getUUID(final String sub, final UUID def) {
        return config.getUUID(concatSubPath(sub), def);
    }

    // ---- defaults ----

    public <D> D getOrSetValueIfAbsent(final String sub, final D def) {
        return config.getOrSetValueIfAbsent(concatSubPath(sub), def);
    }

    public <D> D getOrSetValueIfAbsent(final String sub, final D def, final String comment) {
        return config.getOrSetValueIfAbsent(concatSubPath(sub), def, comment);
    }

    public <D> D getOrSetValueIfAbsent(final String sub, final D def, final String comment,
                                       final CommentType type) {
        return config.getOrSetValueIfAbsent(concatSubPath(sub), def, comment, type);
    }

    public <D> List<D> getOrSetValueIfAbsent(final String sub, final List<D> def) {
        return config.getOrSetValueIfAbsent(concatSubPath(sub), def);
    }

    public <D> List<D> getOrSetValueIfAbsent(final String sub, final List<D> def, final String comment) {
        return config.getOrSetValueIfAbsent(concatSubPath(sub), def, comment);
    }

    public <D> List<D> getOrSetValueIfAbsent(final String sub, final List<D> def, final String comment,
                                             final CommentType type) {
        return config.getOrSetValueIfAbsent(concatSubPath(sub), def, comment, type);
    }

    /** Field-level get-or-seed with merge — the merge counterpart of {@link #getOrSetValueIfAbsent}. */
    public <D> D getOrMergeValue(final String sub, final D def) {
        return config.getOrMergeValue(concatSubPath(sub), def);
    }

    /** As {@link #getOrMergeValue(String, Object)}, landing the completed values on {@code target}. */
    public <D> D getOrMergeValue(final String sub, final D def, final D target) {
        return config.getOrMergeValue(concatSubPath(sub), def, target);
    }

    public void setValueIfAbsent(final String sub, final Object value) {
        config.setValueIfAbsent(concatSubPath(sub), value);
    }

    public void setValueIfAbsent(final String sub, final Object value, final String comment) {
        config.setValueIfAbsent(concatSubPath(sub), value, comment);
    }

    public void setValueIfAbsent(final String sub, final Object value, final String comment,
                                 final CommentType type) {
        config.setValueIfAbsent(concatSubPath(sub), value, comment, type);
    }

    // ---- comments ----

    public void setComment(final String sub, final String comment) {
        config.setComment(concatSubPath(sub), comment);
    }

    public void setComment(final String sub, final String comment, final CommentType type) {
        config.setComment(concatSubPath(sub), comment, type);
    }

    /** Set the comment at {@code sub} only when it has none yet — the mirror of
     *  {@link Config#setDefaultComment(String, String)}. */
    public void setDefaultComment(final String sub, final String comment) {
        config.setDefaultComment(concatSubPath(sub), comment);
    }

    public void setDefaultComment(final String sub, final String comment, final CommentType type) {
        config.setDefaultComment(concatSubPath(sub), comment, type);
    }

    public String getComment(final String sub) {
        return config.getComment(concatSubPath(sub));
    }

    public String getComment(final String sub, final CommentType type) {
        return config.getComment(concatSubPath(sub), type);
    }
}
