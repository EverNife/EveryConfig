package br.com.finalcraft.everyconfig.config.section;

import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.core.comment.CommentType;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A scoped view of a {@link Config} rooted at a path: a cursor over the owning config's single tree,
 * not a copy. Holds only {@code (config, path)} and delegates every call to the owning {@code Config}
 * with the sub-path prefixed. Non-snapshotting: reads are live, so thread-safety is the owning
 * {@code Config}'s contract.
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

    /** The leaf key of this section's path (old {@code getSectionKey}). */
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

    public void setValue(final String sub, final Object value) {
        config.setValue(concatSubPath(sub), value);
    }

    public void setValue(final String sub, final Object value, final String comment) {
        config.setValue(concatSubPath(sub), value, comment);
    }

    public boolean removeValue(final String sub) {
        return config.removeValue(concatSubPath(sub));
    }

    /** Old alias: clears this whole section. */
    public boolean clear() {
        return config.removeValue(path);
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

    public <T> List<T> getList(final String sub, final Class<T> elementType) {
        return config.getList(concatSubPath(sub), elementType);
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

    public <D> List<D> getOrSetValueIfAbsent(final String sub, final List<D> def) {
        return config.getOrSetValueIfAbsent(concatSubPath(sub), def);
    }

    public <D> List<D> getOrSetValueIfAbsent(final String sub, final List<D> def, final String comment) {
        return config.getOrSetValueIfAbsent(concatSubPath(sub), def, comment);
    }

    public void setValueIfAbsent(final String sub, final Object value) {
        config.setValueIfAbsent(concatSubPath(sub), value);
    }

    public void setValueIfAbsent(final String sub, final Object value, final String comment) {
        config.setValueIfAbsent(concatSubPath(sub), value, comment);
    }

    // ---- comments ----

    public void setComment(final String sub, final String comment) {
        config.setComment(concatSubPath(sub), comment);
    }

    public void setComment(final String sub, final String comment, final CommentType type) {
        config.setComment(concatSubPath(sub), comment, type);
    }

    public String getComment(final String sub) {
        return config.getComment(concatSubPath(sub));
    }

    public String getComment(final String sub, final CommentType type) {
        return config.getComment(concatSubPath(sub), type);
    }
}
