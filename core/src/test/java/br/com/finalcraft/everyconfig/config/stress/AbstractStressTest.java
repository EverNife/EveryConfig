package br.com.finalcraft.everyconfig.config.stress;

import br.com.finalcraft.everyconfig.codec.Codec;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.LoadStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Codec-agnostic STRESS + benchmark suite, the parallel/throughput sibling of {@code AbstractConfigTest}.
 * One abstract body, one thin subclass per codec; every method is gated on the {@code everyconfig.stress}
 * system property (set by the Gradle {@code -Pstress} flag), so a normal {@code ./gradlew test} skips the
 * whole suite — it is informational, slow, and memory-hungry, never a CI gate.
 *
 * <p>It exercises the library's central design claim: the heavy Jackson {@link ObjectMapper} is shared per
 * codec across every live {@link Config}, so a config's footprint is just its tree and many configs can be
 * driven concurrently (Jackson mappers are thread-safe after construction). The four scenarios are:
 * <ol>
 *   <li><b>memory</b> — hold N live configs over one shared codec, measure retained heap per config, and
 *       assert every config reports the SAME mapper identity (no per-file engine);</li>
 *   <li><b>parallel distinct configs</b> — T threads each churn their own file (set/save/reload/bind) and
 *       verify integrity, measuring throughput and watching for a hang;</li>
 *   <li><b>shared mapper</b> — T threads bind the same POJO through the shared codec at once, proving the
 *       mapper is safe under concurrent {@code valueToTree}/{@code treeToValue};</li>
 *   <li><b>single-config contention</b> — T threads hammer ONE config's fair save lock, checking it makes
 *       progress and never deadlocks (the failure mode that hangs a per-file-engine design).</li>
 * </ol>
 * Each method appends a section to {@code build/stress-report/<ext>.md}; the companion analysis is written
 * from those numbers.
 */
public abstract class AbstractStressTest {

    /** A fresh codec under test (its mapper is the shared, isolated per-codec default). */
    protected abstract Codec newCodec();

    /** Canonical extension without the dot, e.g. {@code "json"}. */
    protected abstract String fileExtension();

    // ---- knobs (override per machine; cranked by the operator, not CI) ----
    protected int memConfigs() {
        return 20_000;
    }

    protected int threads() {
        return Math.max(4, Runtime.getRuntime().availableProcessors());
    }

    protected long parallelMillis() {
        return 1_500L;
    }

    protected long watchdogSeconds() {
        return 60L;
    }

    protected int inMemoryOps() {
        return 100_000;
    }

    protected int ioOps() {
        return 2_000;
    }

    protected int scaleEntities() {
        return 100_000;
    }

    protected long lockProbeOps() {
        return 2_000_000L;
    }

    static final Path WORK_ROOT = Paths.get("build", "stress-work");
    static final Path REPORT_ROOT = Paths.get("build", "stress-report");
    private static final Map<String, StringBuilder> REPORTS = new ConcurrentHashMap<>();

    private void requireEnabled() {
        Assumptions.assumeTrue(Boolean.getBoolean("everyconfig.stress"),
                "stress suite disabled; run with -Pstress (sets -Deveryconfig.stress=true)");
    }

    private Path workDir() {
        return WORK_ROOT.resolve(fileExtension());
    }

    // ============================================================================
    //  1) Memory: N live configs share one mapper; footprint is just the tree
    // ============================================================================

    @Test
    @DisplayName("[stress] N live configs share one mapper and cost only their tree")
    void memory_manyConfigsShareOneMapper() throws IOException {
        requireEnabled();
        final int n = memConfigs();
        final Codec codec = newCodec();
        final Path dir = freshDir("mem");

        List<Config> held = new ArrayList<>(n);
        final long base = usedHeap();
        for (int i = 0; i < n; i++) {
            final Config c = Config.open(dir.resolve("c" + i + "." + fileExtension()), codec);
            populate(c, i);
            held.add(c);
        }
        final long after = usedHeap();
        final long perConfig = (after - base) / n;

        // The design claim: one shared mapper behind every config (no per-file engine).
        final ObjectMapper shared = held.get(0).getCodec().getObjectMapper();
        boolean allShared = true;
        for (final Config c : held) {
            if (c.getCodec().getObjectMapper() != shared) {
                allShared = false;
                break;
            }
        }
        assertTrue(allShared, "every config must share the one per-codec ObjectMapper");

        held.clear();
        held = null;
        usedHeap();

        report("### Memory (shared engine)",
                kv("configs held", String.format("%,d", n)),
                kv("retained / config", String.format("~%,d bytes", perConfig)),
                kv("retained total", String.format("~%,d MB", (perConfig * (long) n) / (1024 * 1024))),
                kv("one shared mapper", String.valueOf(allShared)));
        cleanup(dir);
    }

    // ============================================================================
    //  2) Parallel distinct configs: throughput + integrity, watchdog for hangs
    // ============================================================================

    @Test
    @DisplayName("[stress] T threads churn their own configs in parallel without corruption")
    void parallel_distinctConfigs_throughputAndIntegrity() throws Exception {
        requireEnabled();
        final int t = threads();
        final Codec codec = newCodec();
        final Path dir = freshDir("par");
        final long deadline = System.currentTimeMillis() + parallelMillis();

        final AtomicLong ops = new AtomicLong();
        final AtomicLong mismatches = new AtomicLong();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final CountDownLatch start = new CountDownLatch(1);

        final List<Runnable> work = new ArrayList<>();
        for (int w = 0; w < t; w++) {
            final int worker = w;
            work.add(() -> {
                await(start);
                final Path file = dir.resolve("w" + worker + "." + fileExtension());
                long iter = 0;
                try {
                    while (System.currentTimeMillis() < deadline) {
                        final Config c = Config.open(file, codec);
                        final String name = "w" + worker + "-i" + iter;
                        final int port = (int) (iter % 65_535);
                        c.setValue("server.name", name);
                        c.setValue("server.port", port);
                        c.setValue("tags", Arrays.asList("a", "b", String.valueOf(iter)));
                        c.getOrSetValueIfAbsent("seeded", 7);
                        c.setComment("server.port", "the listen port");
                        c.save();

                        final Config r = Config.open(file, codec);
                        if (!name.equals(r.getString("server.name")) || port != r.getInt("server.port")
                                || r.getStringList("tags").size() != 3) {
                            mismatches.incrementAndGet();
                        }
                        c.close();
                        r.close();
                        ops.incrementAndGet();
                        iter++;
                    }
                } catch (final Throwable ex) {
                    errors.add(ex);
                }
            });
        }

        final boolean finished = runAll(work, start);
        if (!finished) {
            dumpThreadsAndFail("parallel_distinctConfigs hung past the watchdog");
        }
        if (!errors.isEmpty()) {
            fail("worker threads threw: " + errors.get(0), errors.get(0));
        }
        assertEquals(0L, mismatches.get(), "no config may read back corrupted under parallel churn");

        final long total = ops.get();
        report("### Parallel — distinct configs (set/save/reload/verify)",
                kv("threads", String.valueOf(t)),
                kv("duration", parallelMillis() + " ms"),
                kv("total round-trips", String.format("%,d", total)),
                kv("throughput", String.format("%,d round-trips/sec", total * 1000L / Math.max(1L, parallelMillis()))),
                kv("integrity mismatches", String.valueOf(mismatches.get())),
                kv("deadlock", "no"));
        cleanup(dir);
    }

    // ============================================================================
    //  3) Shared mapper: concurrent bind through the one mapper is thread-safe
    // ============================================================================

    @Test
    @DisplayName("[stress] T threads bind through the shared mapper concurrently and round-trip cleanly")
    void parallel_sharedMapper_bindThreadSafety() throws Exception {
        requireEnabled();
        final int t = threads();
        final Codec codec = newCodec();
        final long deadline = System.currentTimeMillis() + parallelMillis();

        final AtomicLong binds = new AtomicLong();
        final AtomicLong mismatches = new AtomicLong();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final CountDownLatch start = new CountDownLatch(1);

        final List<Runnable> work = new ArrayList<>();
        for (int w = 0; w < t; w++) {
            final int worker = w;
            work.add(() -> {
                await(start);
                long iter = 0;
                try {
                    while (System.currentTimeMillis() < deadline) {
                        // A distinct in-memory Config per iteration, all over the one shared mapper.
                        final Config c = Config.inMemory(codec);
                        final StressPojo src = StressPojo.sample(worker, iter);
                        c.setValue("cfg", src);
                        final StressPojo back = c.getValue("cfg", StressPojo.class);
                        if (!src.equals(back)) {
                            mismatches.incrementAndGet();
                        }
                        binds.incrementAndGet();
                        iter++;
                    }
                } catch (final Throwable ex) {
                    errors.add(ex);
                }
            });
        }

        final boolean finished = runAll(work, start);
        if (!finished) {
            dumpThreadsAndFail("parallel_sharedMapper hung past the watchdog");
        }
        if (!errors.isEmpty()) {
            fail("binding threads threw: " + errors.get(0), errors.get(0));
        }
        assertEquals(0L, mismatches.get(), "concurrent bind through the shared mapper must round-trip exactly");

        final long total = binds.get();
        report("### Parallel — shared mapper (concurrent bind)",
                kv("threads", String.valueOf(t)),
                kv("duration", parallelMillis() + " ms"),
                kv("total binds", String.format("%,d", total)),
                kv("throughput", String.format("%,d binds/sec", total * 1000L / Math.max(1L, parallelMillis()))),
                kv("round-trip mismatches", String.valueOf(mismatches.get())));
    }

    // ============================================================================
    //  4) Single-config contention. A Config is a mutable handle whose ONLY lock is
    //     on save(); setValue/save are not internally synchronized, so a single
    //     Config is not a supported concurrent-WRITE target. (4a) characterizes the
    //     unsynchronized behavior (fail only on a true hang); (4b) proves external
    //     synchronization makes the same workload safe.
    // ============================================================================

    @Test
    @DisplayName("[stress] one config under UNSYNCHRONIZED concurrent mutation: characterize, fail only on a hang")
    void parallel_singleConfig_unsynchronizedCharacterization() throws Exception {
        requireEnabled();
        final int t = threads();
        final Codec codec = newCodec();
        final Path dir = freshDir("one-unsync");
        final Config shared = Config.open(dir.resolve("shared." + fileExtension()), codec);
        final long deadline = System.currentTimeMillis() + parallelMillis();

        final AtomicLong ops = new AtomicLong();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final CountDownLatch start = new CountDownLatch(1);

        final List<Runnable> work = new ArrayList<>();
        for (int w = 0; w < t; w++) {
            final int worker = w;
            work.add(() -> {
                await(start);
                long iter = 0;
                try {
                    while (System.currentTimeMillis() < deadline) {
                        shared.setValue("w" + worker, iter);          // mutate the shared tree, no lock
                        shared.getInt("w" + worker, -1);
                        if ((iter & 0x3F) == 0) {
                            shared.save();                            // save() iterates tree+comments while others mutate
                        }
                        ops.incrementAndGet();
                        iter++;
                    }
                } catch (final Throwable ex) {
                    errors.add(ex);
                }
            });
        }

        final boolean finished = runAll(work, start);
        if (!finished) {
            dumpThreadsAndFail("single-config hammer hung past the watchdog (possible deadlock)");
        }
        try {
            shared.close();
        } catch (final Throwable ignored) {
            // the tree may be mid-mutation; closing is best-effort here
        }
        // An UNSYNCHRONIZED single Config is NOT a supported write target, so worker exceptions here are an
        // expected characterization, not a test failure — only a hang/deadlock above is a real defect. The
        // companion test below proves external synchronization removes these errors.
        final String firstError = errors.isEmpty() ? "none" : errors.get(0).getClass().getName();
        report("### Parallel — single config, UNSYNCHRONIZED (characterization)",
                kv("threads", String.valueOf(t)),
                kv("ops attempted", String.format("%,d", ops.get())),
                kv("worker exceptions", String.format("%,d", (long) errors.size())),
                kv("first exception", firstError),
                kv("deadlock / hang", "no"),
                kv("verdict", errors.isEmpty()
                        ? "no error observed this run (still unsafe by contract)"
                        : "concurrent unsynchronized mutation is unsafe — confine a Config to one thread or guard it"));
        cleanup(dir);
    }

    @Test
    @DisplayName("[stress] one config under EXTERNALLY SYNCHRONIZED concurrent access is safe")
    void parallel_singleConfig_externallySynchronized_isSafe() throws Exception {
        requireEnabled();
        final int t = threads();
        final Codec codec = newCodec();
        final Path dir = freshDir("one-sync");
        final Config shared = Config.open(dir.resolve("shared." + fileExtension()), codec);
        final Object guard = new Object();
        final long deadline = System.currentTimeMillis() + parallelMillis();

        final AtomicLong ops = new AtomicLong();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final CountDownLatch start = new CountDownLatch(1);

        final List<Runnable> work = new ArrayList<>();
        for (int w = 0; w < t; w++) {
            final int worker = w;
            work.add(() -> {
                await(start);
                long iter = 0;
                try {
                    while (System.currentTimeMillis() < deadline) {
                        synchronized (guard) {                        // ALL access under one external lock
                            shared.setValue("w" + worker, iter);
                            shared.getInt("w" + worker, -1);
                            if ((iter & 0x3F) == 0) {
                                shared.save();
                            }
                        }
                        ops.incrementAndGet();
                        iter++;
                    }
                } catch (final Throwable ex) {
                    errors.add(ex);
                }
            });
        }

        final boolean finished = runAll(work, start);
        if (!finished) {
            dumpThreadsAndFail("externally-synchronized single-config hung past the watchdog");
        }
        shared.close();
        if (!errors.isEmpty()) {
            fail("external synchronization must eliminate concurrent-access errors, but saw: " + errors.get(0),
                    errors.get(0));
        }

        final long total = ops.get();
        report("### Parallel — single config, EXTERNALLY SYNCHRONIZED (safe pattern)",
                kv("threads", String.valueOf(t)),
                kv("duration", parallelMillis() + " ms"),
                kv("total ops", String.format("%,d", total)),
                kv("throughput", String.format("%,d ops/sec", total * 1000L / Math.max(1L, parallelMillis()))),
                kv("worker exceptions", "0"),
                kv("deadlock", "no"));
        cleanup(dir);
    }

    // ============================================================================
    //  5) Single-thread throughput per operation (ops/sec)
    // ============================================================================

    @Test
    @DisplayName("[stress] single-thread throughput per operation")
    void throughput_singleThreadOps() throws Exception {
        requireEnabled();
        final Codec codec = newCodec();
        final Path dir = freshDir("tput");
        final int n = inMemoryOps();
        final int io = ioOps();

        // in-memory ops over one config (bounded keyspace so the tree does not grow unbounded)
        final Config c = Config.inMemory(codec);
        final long setOps = opsPerSec(n, i -> c.setValue("k" + (i & 0xFFFF), i));
        final long getOps = opsPerSec(n, i -> c.getInt("k" + (i & 0xFFFF), -1));
        final long getOrSetOps = opsPerSec(n, i -> c.getOrSetValueIfAbsent("seed" + (i & 0xFF), 1));
        final StressPojo pojo = StressPojo.sample(1, 2);
        final long bindWrite = opsPerSec(n, i -> c.setValue("p", pojo));
        final long bindRead = opsPerSec(n, i -> c.getValue("p", StressPojo.class));

        // I/O ops over a real file (atomic write dominates, so a smaller count)
        final Config f = Config.open(dir.resolve("t." + fileExtension()), codec);
        f.setValue("server.host", "localhost");
        f.setValue("server.port", 25565);
        f.setValue("tags", Arrays.asList("a", "b", "c"));
        final long saveOps = opsPerSec(io, i -> f.save());
        final long saveReloadOps = opsPerSec(io, i -> {
            f.setValue("n", i);
            f.save();
            f.reload();
        });
        f.close();

        report("### Throughput — single thread (ops/sec, higher is better)",
                "| operation | ops/sec |",
                "|---|---:|",
                row("setValue (scalar, in-memory)", setOps),
                row("getInt (in-memory)", getOps),
                row("getOrSetValueIfAbsent (present)", getOrSetOps),
                row("bind write — setValue(pojo)", bindWrite),
                row("bind read — getValue(type)", bindRead),
                row("save (atomic file write)", saveOps),
                row("save + reload round-trip", saveReloadOps));
        cleanup(dir);
    }

    // ============================================================================
    //  6) Scale: one config with N entities — build / save / reopen / size
    // ============================================================================

    @Test
    @DisplayName("[stress] scale — one config holding N entities")
    void scale_largeConfig() throws Exception {
        requireEnabled();
        final Codec codec = newCodec();
        final Path dir = freshDir("scale");
        final int n = scaleEntities();
        final Path file = dir.resolve("big." + fileExtension());

        final Config c = Config.open(file, codec);
        final long b0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            c.setValue("entities.e" + i + ".name", "name_" + i);
            c.setValue("entities.e" + i + ".value", i);
        }
        final long buildMs = msSince(b0);

        final long s0 = System.nanoTime();
        c.save();
        final long saveMs = msSince(s0);
        final long fileBytes = Files.size(file);

        final long o0 = System.nanoTime();
        final Config r = Config.open(file, codec);
        final long parseMs = msSince(o0);
        // Characterize the reopen: a codec that cannot parse the file at this scale reads back as a backed-up
        // empty tree (a visible negative result), so only assert integrity when the parse actually succeeded.
        final LoadStatus status = r.lastLoadStatus();
        final int deepKeys;
        final long deepKeysMs;
        if (status == LoadStatus.OK) {
            assertEquals(n - 1, r.getInt("entities.e" + (n - 1) + ".value"),
                    "the last entity must survive the round-trip");
            final long k0 = System.nanoTime();
            deepKeys = r.getKeys("entities", true).size();
            deepKeysMs = msSince(k0);
        } else {
            deepKeys = 0;
            deepKeysMs = 0;
        }

        c.close();
        r.close();
        report("### Scale — one config with N entities",
                kv("entities (2 keys each)", String.format("%,d", n)),
                kv("build (setValue x2N)", buildMs + " ms"),
                kv("save (encode + atomic write)", saveMs + " ms"),
                kv("on-disk size", String.format("%,d KB", fileBytes / 1024)),
                kv("reopen + parse", parseMs + " ms"),
                kv("reopen status", status.name()),
                kv("getKeys(deep) count / time", String.format("%,d in %d ms", (long) deepKeys, deepKeysMs)));
        cleanup(dir);
    }

    // ============================================================================
    //  7) Lock cost: what full thread-safety would actually buy and cost
    // ============================================================================

    @Test
    @DisplayName("[stress] lock cost — uncontended tax and serialized-vs-parallel throughput")
    void lockCost_serializationVsParallel() throws Exception {
        requireEnabled();
        final Codec codec = newCodec();
        final long probe = lockProbeOps();

        // (a) uncontended per-op tax: the SAME setValue with and without taking a lock each call.
        final Config c = Config.inMemory(codec);
        final ReentrantLock lk = new ReentrantLock();
        final long plain = opsPerSec(probe, i -> c.setValue("k", i));
        final long locked = opsPerSec(probe, i -> {
            lk.lock();
            try {
                c.setValue("k", i);
            } finally {
                lk.unlock();
            }
        });
        final double taxNs = Math.max(0.0, (1e9 / Math.max(1L, locked)) - (1e9 / Math.max(1L, plain)));

        // (b) the real cost of "make a single Config thread-safe" = funnel every thread through one lock.
        // Parallel = the SUPPORTED model (a Config per thread, lock-free). Serialized = one shared Config
        // guarded by one lock. The ratio is the throughput you forfeit by serializing.
        final int t = threads();
        final long parallel = parallelSetThroughput(codec, t, false);
        final long serialized = parallelSetThroughput(codec, t, true);
        final double ratio = serialized == 0 ? 0 : (double) parallel / serialized;

        report("### Lock cost — full Config thread-safety would serialize the hot path",
                kv("uncontended setValue, no lock", String.format("%,d ops/sec", plain)),
                kv("uncontended setValue, lock each op", String.format("%,d ops/sec", locked)),
                kv("uncontended lock tax", String.format("~%.0f ns/op", taxNs)),
                kv(t + " threads — parallel (a Config per thread, lock-free)", String.format("%,d ops/sec", parallel)),
                kv(t + " threads — serialized (one shared Config, one lock)", String.format("%,d ops/sec", serialized)),
                kv("parallel ÷ serialized", String.format("%.1fx", ratio)),
                kv("reading", "serializing every op on one lock forfeits ~" + String.format("%.1fx", ratio)
                        + " throughput — the parallelism that the shared mapper exists to provide"));
    }

    // ============================================================================
    //  harness
    // ============================================================================

    /** ops/sec for {@code count} invocations of {@code op}, indexed by the iteration counter. */
    private long opsPerSec(final long count, final LongConsumer op) {
        final long t0 = System.nanoTime();
        for (long i = 0; i < count; i++) {
            op.accept(i);
        }
        final long ns = System.nanoTime() - t0;
        return ns <= 0 ? 0 : count * 1_000_000_000L / ns;
    }

    /** T threads call setValue for {@code parallelMillis}; shared = one Config under one lock (serialized),
     *  else a Config per thread (lock-free). Returns ops/sec. */
    private long parallelSetThroughput(final Codec codec, final int t, final boolean shared) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + parallelMillis();
        final AtomicLong ops = new AtomicLong();
        final CountDownLatch start = new CountDownLatch(1);
        final Config one = shared ? Config.inMemory(codec) : null;
        final Object guard = new Object();
        final List<Runnable> work = new ArrayList<>();
        for (int w = 0; w < t; w++) {
            final int worker = w;
            work.add(() -> {
                await(start);
                final Config mine = shared ? one : Config.inMemory(codec);
                long i = 0;
                while (System.currentTimeMillis() < deadline) {
                    if (shared) {
                        synchronized (guard) {
                            mine.setValue("k" + worker, i);
                        }
                    } else {
                        mine.setValue("k", i);
                    }
                    ops.incrementAndGet();
                    i++;
                }
            });
        }
        runAll(work, start);
        return ops.get() * 1000L / Math.max(1L, parallelMillis());
    }

    private static String row(final String name, final long opsPerSec) {
        return "| " + name + " | " + String.format("%,d", opsPerSec) + " |";
    }

    private static long msSince(final long nanoStart) {
        return (System.nanoTime() - nanoStart) / 1_000_000L;
    }

    /** Run all tasks on a fixed pool, release the start latch, and await within the watchdog. */
    private boolean runAll(final List<Runnable> work, final CountDownLatch start) throws InterruptedException {
        final ExecutorService pool = Executors.newFixedThreadPool(work.size());
        for (final Runnable r : work) {
            pool.submit(r);
        }
        start.countDown();
        pool.shutdown();
        final boolean done = pool.awaitTermination(watchdogSeconds(), TimeUnit.SECONDS);
        if (!done) {
            pool.shutdownNow();
        }
        return done;
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void dumpThreadsAndFail(final String message) {
        final ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        final long[] deadlocked = mx.findDeadlockedThreads();
        final StringBuilder sb = new StringBuilder(message).append('\n');
        if (deadlocked != null) {
            sb.append("DEADLOCK detected on ").append(deadlocked.length).append(" threads:\n");
            for (final ThreadInfo info : mx.getThreadInfo(deadlocked, true, true)) {
                sb.append(info);
            }
        } else {
            sb.append("no JVM-reported deadlock; threads exceeded the watchdog (livelock or slow run)\n");
        }
        report("### FAILURE", kv("watchdog", message), kv("deadlock", deadlocked != null ? "YES" : "none reported"));
        fail(sb.toString());
    }

    private static long usedHeap() {
        final Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            rt.gc();
            try {
                Thread.sleep(40);
            } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    /** Populate one config with a representative shape: scalars, a nested section, a list, a comment. */
    private void populate(final Config c, final int i) {
        c.setValue("uuid", "550e8400-e29b-41d4-a716-44665544" + String.format("%04d", i % 10_000));
        c.setValue("name", "config_" + i);
        c.setValue("server.host", "10.0.0." + (i % 255));
        c.setValue("server.port", 25_000 + (i % 1_000));
        c.setValue("tags", Arrays.asList("alpha", "beta", "gamma"));
        c.setComment("server.port", "listen port");
    }

    private Path freshDir(final String name) throws IOException {
        final Path dir = workDir().resolve(name);
        deleteRecursive(dir);
        Files.createDirectories(dir);
        return dir;
    }

    private void cleanup(final Path dir) {
        deleteRecursive(dir);
    }

    private static void deleteRecursive(final Path path) {
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

    private static String kv(final String key, final String value) {
        return "- **" + key + "**: " + value;
    }

    /** Append a titled section to this codec's running report and flush it to disk. */
    private void report(final String title, final String... lines) {
        final StringBuilder sb = REPORTS.computeIfAbsent(fileExtension(), ext -> {
            final StringBuilder head = new StringBuilder();
            head.append("# EveryConfig stress report — ").append(ext).append(" codec\n\n");
            head.append("threads=").append(threads())
                    .append(", parallel=").append(parallelMillis()).append("ms")
                    .append(", memConfigs=").append(memConfigs()).append("\n\n");
            return head;
        });
        synchronized (sb) {
            sb.append(title).append('\n');
            for (final String line : lines) {
                sb.append(line).append('\n');
            }
            sb.append('\n');
            try {
                Files.createDirectories(REPORT_ROOT);
                Files.write(REPORT_ROOT.resolve(fileExtension() + ".md"),
                        sb.toString().getBytes(StandardCharsets.UTF_8));
            } catch (final IOException e) {
                System.out.println("[stress] failed to write report: " + e);
            }
        }
        System.out.println("[stress " + fileExtension() + "] " + title + " " + Arrays.toString(lines));
    }

    // ---- a moderately rich POJO for the binding-thread-safety test (no non-finite edges) ----

    @Data
    @NoArgsConstructor
    public static class StressPojo {
        public String name;
        public int count;
        public long epoch;
        public boolean active;
        public List<String> tags;
        public Map<String, Integer> scores;
        public Inner inner;

        @Data
        @NoArgsConstructor
        public static class Inner {
            public String url;
            public int poolSize;
        }

        static StressPojo sample(final int worker, final long iter) {
            final StressPojo p = new StressPojo();
            p.name = "w" + worker + "-i" + iter;
            p.count = (int) (iter % 1000);
            p.epoch = 1_700_000_000_000L + iter;
            p.active = (iter & 1L) == 0L;
            p.tags = Arrays.asList("t" + worker, "i" + (iter % 7), "shared");
            final Map<String, Integer> scores = new LinkedHashMap<>();
            scores.put("a", worker);
            scores.put("b", (int) (iter % 13));
            p.scores = scores;
            final Inner inner = new Inner();
            inner.url = "jdbc:db:" + worker;
            inner.poolSize = 4 + worker;
            p.inner = inner;
            return p;
        }
    }
}
