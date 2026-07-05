package br.com.finalcraft.everyconfig.io.watcher;

/**
 * Immutable (mtime, size) tuple, optionally carrying a content hash. An absent file is {@code (0, -1)}.
 * The hash is {@link #NO_HASH} for the cheap stat-only fingerprint and a real CRC32 when content was
 * read; a stat-only fingerprint still compares value-equal to a hashed one on (mtime, size), so the
 * O(1) path stays compatible while two hashed fingerprints additionally distinguish same-size content.
 */
public final class Fingerprint {

    /**
     * Content hash sentinel meaning "not computed"; such a fingerprint compares on (mtime, size) only.
     */
    public static final long NO_HASH = 0L;

    public static final Fingerprint ABSENT = new Fingerprint(0L, -1L);

    public final long mtime;
    public final long size;
    public final long hash;

    public Fingerprint(final long mtime, final long size) {
        this(mtime, size, NO_HASH);
    }

    public Fingerprint(final long mtime, final long size, final long hash) {
        this.mtime = mtime;
        this.size = size;
        this.hash = hash;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Fingerprint)) {
            return false;
        }
        final Fingerprint other = (Fingerprint) o;
        if (mtime != other.mtime || size != other.size) {
            return false;
        }
        // The content hash refines the comparison only when both sides carry one, so a stat-only
        // fingerprint stays equal to a hashed one on (mtime, size) and the cheap path is unaffected.
        if (hash != NO_HASH && other.hash != NO_HASH) {
            return hash == other.hash;
        }
        return true;
    }

    @Override
    public int hashCode() {
        // Equality can fall back to (mtime, size) when a hash is absent, so the hash must NOT enter the
        // hashCode — equal fingerprints must always share one.
        return (int) (mtime * 31 + size);
    }

    @Override
    public String toString() {
        return "Fingerprint(mtime=" + mtime + ", size=" + size + ", hash=" + hash + ")";
    }
}
