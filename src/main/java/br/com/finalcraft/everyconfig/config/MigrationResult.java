package br.com.finalcraft.everyconfig.config;

/**
 * The outcome of a {@link Config#migrateKey(String, String)} call. The data move alone is
 * indistinguishable across the no-op cases (the tree looks the same whether the source was already moved
 * or never existed), so the result is returned to let a caller tell a real move from the benign re-run
 * from the suspicious typo. {@code migrateKey} stays safe to run unconditionally on every startup.
 */
public enum MigrationResult {

    /** The source was moved to the destination. If the destination already held a value it was
     *  overwritten — the source wins. */
    MOVED,

    /** Nothing to do: {@code oldPath} and {@code newPath} are the same path. */
    SAME_PATH,

    /** Nothing to do: {@code oldPath} or {@code newPath} is the root, which cannot be migrated. */
    INVALID_ROOT,

    /** The source is absent but the destination already exists — the migration ran on an earlier startup
     *  and this is a benign re-run. No warning warranted. */
    ALREADY_MIGRATED,

    /** Neither the source nor the destination exists — nothing was migrated. Often a caller typo in
     *  {@code oldPath}; worth logging, as the data stays unmigrated. */
    SOURCE_ABSENT;

    /** True only for {@link #MOVED} — the single outcome that changed the tree this call. */
    public boolean moved() {
        return this == MOVED;
    }
}
