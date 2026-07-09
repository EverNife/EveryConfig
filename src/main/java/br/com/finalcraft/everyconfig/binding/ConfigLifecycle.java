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
