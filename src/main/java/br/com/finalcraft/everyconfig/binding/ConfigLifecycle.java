package br.com.finalcraft.everyconfig.binding;

/**
 * Opt-in lifecycle interface for a bound entity that wants generic hooks around its own read and write —
 * the richer sibling of the method-level {@link br.com.finalcraft.everyconfig.annotation.PreLoad @PreLoad}/
 * {@link br.com.finalcraft.everyconfig.annotation.PostLoad @PostLoad}/
 * {@link br.com.finalcraft.everyconfig.annotation.PreSave @PreSave}/
 * {@link br.com.finalcraft.everyconfig.annotation.PostSave @PostSave} annotations. Each callback receives the
 * same {@link ConfigContext} the annotations do, so it can read siblings or reach the raw tree (via
 * {@code ctx.section().getConfig()}) for advanced customization.
 *
 * <p>The hooks fire around the POJO&lt;-&gt;tree binding, not the file flush: {@code preSave}/{@code postSave}
 * run around the merge into the tree (which precedes {@code Config.save}), and {@code preLoad}/{@code postLoad}
 * around the bind from the tree. All four default to no-ops, so an implementor overrides only what it needs.
 *
 * <p><b>Nested composition.</b> The hooks fire wherever EveryConfig (de)serializes this type — not only as
 * the top-level value bound to a path, but also as a nested POJO field, a {@code Map} value, or a
 * {@code List}/{@code Set}/array element, at any depth. Each nested hook receives a {@link ConfigContext}
 * whose {@link ConfigContext#section() section()} points at that value's real sub-path ({@code owner.field},
 * {@code owner.<mapKey>}, {@code list[i]}, or the {@code @KeyIndex} section), so a nested {@code postSave}/
 * {@code postLoad} can reach its own slice of the tree exactly as a top-level one can. Two exceptions:
 * {@code preLoad} fires for the top-level value only (a nested instance does not exist before its own bind),
 * and a type persisted as a compact list element ({@code @EveryConfigCompactValue}) has no sub-path, so its
 * hooks do not compose there (EveryConfig logs a warning rather than silently skipping).
 *
 * @see ConfigContext
 * @see br.com.finalcraft.everyconfig.annotation.PreLoad
 * @see br.com.finalcraft.everyconfig.annotation.PostLoad
 * @see br.com.finalcraft.everyconfig.annotation.PreSave
 * @see br.com.finalcraft.everyconfig.annotation.PostSave
 */
public interface ConfigLifecycle {

    /** Before the tree is bound onto this entity. Interface form of {@link br.com.finalcraft.everyconfig.annotation.PreLoad @PreLoad}. */
    default void preLoad(final ConfigContext context) {
    }

    /**
     * After this entity has been bound from the tree ({@code context.issues()} is populated here). Interface
     * form of {@link br.com.finalcraft.everyconfig.annotation.PostLoad @PostLoad}.
     */
    default void postLoad(final ConfigContext context) {
    }

    /** Before this entity is merged into the tree. Interface form of {@link br.com.finalcraft.everyconfig.annotation.PreSave @PreSave}. */
    default void preSave(final ConfigContext context) {
    }

    /** After this entity has been merged into the tree. Interface form of {@link br.com.finalcraft.everyconfig.annotation.PostSave @PostSave}. */
    default void postSave(final ConfigContext context) {
    }
}
