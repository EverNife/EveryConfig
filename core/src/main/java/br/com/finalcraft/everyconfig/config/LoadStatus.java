package br.com.finalcraft.everyconfig.config;

/**
 * Outcome of the most recent load or reload. Pollable, so a reload that keeps stale state on a parse
 * failure is observable instead of silent.
 */
public enum LoadStatus {

    /** Freshly constructed, not yet loaded from a back-store. */
    NEVER_LOADED,
    /** Clean parse; the tree is the file. */
    OK,
    /** No file; the tree was kept or started empty. */
    ABSENT,
    /** The file exists but is zero-length; an empty tree is used. */
    EMPTY,
    /** Initial open could not parse the file: it was backed up and the tree started empty. */
    PARSE_FAILED_BACKED_UP,
    /** A reload could not parse the file: the live tree was kept and the file left untouched (divergence). */
    PARSE_FAILED_KEPT
}
