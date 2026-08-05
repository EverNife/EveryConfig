package br.com.finalcraft.everyconfig.testkit;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.codec.CommentFidelity;
import br.com.finalcraft.everyconfig.config.Config;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The harness every codec-agnostic suite runs on: one abstract body, one thin subclass per codec, and the
 * capability hooks that let a format opt out of what it cannot represent (skip via {@link Assumptions},
 * never fail). It carries no assertion of its own — a suite supplies the contract, this supplies the ground
 * to run it on, so a module beyond the core can reuse the same matrix.
 *
 * <p><b>Residuals.</b> Real files are written under {@code build/test-residuals/<group>/<ext>/<method>} and
 * kept for inspection unless {@link #CLEAN_TEST_RESIDUALS} is flipped — deliberately not {@code @TempDir},
 * so the flag controls deletion and the same scenario can be diffed across formats.
 */
public abstract class CodecMatrixTest {

    // ===================== required hooks (each subclass implements) =====================

    /** A fresh codec instance under test. */
    protected abstract Codec newCodec();

    /** Canonical file extension WITHOUT the leading dot, e.g. {@code "json"}, {@code "yaml"}. */
    protected abstract String fileExtension();

    /** The comment round-trip fidelity this codec declares. */
    protected abstract CommentFidelity fidelity();

    // ===================== overridable capability flags =====================

    /** True when block/side/header comments survive a round-trip. Derived from {@link #fidelity()}. */
    protected boolean supportsComments() {
        return fidelity() != CommentFidelity.NONE;
    }

    /** True when the format can represent an explicit null (TOML cannot). */
    protected boolean supportsNull() {
        return true;
    }

    /** True only when comments round-trip losslessly (YAML/TOML); excludes LOSSY (JSONC) and NONE (JSON). */
    protected boolean supportsLosslessComments() {
        return fidelity() == CommentFidelity.LOSSLESS;
    }

    /** True when per-element block comments on a scalar list (addressed as {@code list.i}) round-trip. */
    protected boolean supportsListItemComments() {
        return false;
    }

    /** True when the emitter honors key-ordering pins (FIRST/LAST zones). JSON's plain output has no
     *  structure emitter, so it keeps live-tree order — overridden off there. */
    protected boolean supportsKeyOrdering() {
        return true;
    }

    /** The line-comment marker of this codec's dialect, so a layout assertion stays format-agnostic. */
    protected String commentMarker() {
        return "#";
    }

    /** A document that this codec's parser must reject (drives the parse-fail / backup path). */
    protected String malformedText() {
        return "   not a valid document   ";
    }

    // ===================== residuals harness =====================

    /** Flip to true to delete all residual files after the run; false keeps them for inspection. */
    public static final boolean CLEAN_TEST_RESIDUALS = false;

    /** Root for inspectable residual files (hand-rolled, NOT {@code @TempDir}). */
    public static final Path RESIDUALS_ROOT = Paths.get("build", "test-residuals");

    /** The folder this suite's residuals land in, under {@link #RESIDUALS_ROOT}: suites that write the same
     *  scenario over different contracts stay side by side instead of overwriting each other. */
    protected String residualsGroup() {
        return "config";
    }

    protected Codec codec;
    protected Path residualDir;
    private final List<Config> opened = new ArrayList<Config>();

    @BeforeEach
    void setUpCodecMatrix(final TestInfo info) {
        codec = newCodec();
        final String method = info.getTestMethod().map(Method::getName).orElse("unknown");
        residualDir = RESIDUALS_ROOT.resolve(residualsGroup()).resolve(fileExtension()).resolve(method);
        deleteQuietly(residualDir); // clean slate at start; last run's artifacts survive afterwards
        residualDir.toFile().mkdirs();
    }

    @AfterEach
    void tearDownCodecMatrix() {
        for (final Config c : opened) {
            try {
                c.close();
            } catch (final Exception ignored) {
                // idempotent close; a failure must not mask the test result
            }
        }
        opened.clear();
        if (CLEAN_TEST_RESIDUALS) {
            deleteQuietly(residualDir);
        }
    }

    @AfterAll
    static void handleResiduals() {
        if (CLEAN_TEST_RESIDUALS) {
            deleteQuietly(RESIDUALS_ROOT);
        } else {
            System.out.println("[CodecMatrixTest] CLEAN_TEST_RESIDUALS=false - keeping residuals at:");
            System.out.println("  -> " + RESIDUALS_ROOT.toAbsolutePath());
        }
    }

    // ===================== helpers =====================

    protected Path file() {
        return residualDir.resolve("config." + fileExtension());
    }

    /** Opens a Config over {@link #file()}, tracked for teardown close. */
    protected Config open() {
        return open(file());
    }

    protected Config open(final Path path) {
        final Config c = Config.open(path, codec);
        opened.add(c);
        return c;
    }

    /** Tracks an already-opened config for teardown close — for a config the test built itself. */
    protected Config track(final Config config) {
        opened.add(config);
        return config;
    }

    protected String readText() throws IOException {
        return readText(file());
    }

    protected String readText(final Path path) throws IOException {
        return normalize(new String(Files.readAllBytes(path), codec.charset()));
    }

    protected void writeText(final String text) throws IOException {
        writeText(file(), text);
    }

    protected void writeText(final Path path, final String text) throws IOException {
        Files.write(path, text.getBytes(codec.charset()));
    }

    protected static String normalize(final String s) {
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }

    // ===================== assumptions =====================

    protected void assumeComments() {
        Assumptions.assumeTrue(supportsComments(),
                "codec '" + fileExtension() + "' has no comment fidelity");
    }

    protected void assumeNullSupported() {
        Assumptions.assumeTrue(supportsNull(),
                "codec '" + fileExtension() + "' has no explicit-null representation");
    }

    protected void assumeLosslessComments() {
        Assumptions.assumeTrue(supportsLosslessComments(),
                "codec '" + fileExtension() + "' does not round-trip comments losslessly");
    }

    protected void assumeListItemComments() {
        Assumptions.assumeTrue(supportsListItemComments(),
                "codec '" + fileExtension() + "' does not round-trip per-list-item comments");
    }

    protected void assumeKeyOrdering() {
        Assumptions.assumeTrue(supportsKeyOrdering(),
                "codec '" + fileExtension() + "' does not honor key-ordering pins");
    }

    public static void deleteQuietly(final Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walk(path).sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (final IOException ignored) {
                    // best-effort
                }
            });
        } catch (final IOException ignored) {
            // best-effort
        }
    }
}
