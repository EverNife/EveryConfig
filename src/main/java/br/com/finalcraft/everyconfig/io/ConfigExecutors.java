package br.com.finalcraft.everyconfig.io;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared executor for asynchronous config I/O (the {@code saveAsync} path).
 *
 * <p>On Java 21+, uses virtual threads via reflection (zero-overhead concurrency for I/O).
 * On older JVMs (8-20), falls back to a daemon thread pool with {@code max(2, cores)} core
 * threads that time out after 3 seconds of idleness, so an idle application holds no threads.
 *
 * <p><b>Why core threads instead of max threads:</b> a {@link ThreadPoolExecutor} with an
 * unbounded queue never grows past its core size - excess tasks wait in the queue instead of
 * spawning new threads. The fallback pool therefore declares all of its threads as core
 * threads (created on demand) and enables {@link ThreadPoolExecutor#allowCoreThreadTimeOut}.
 *
 * <p><b>Nested blocking caution (fallback pool only):</b> blocking on a save future from
 * <em>inside</em> a task already running on this executor holds a pool thread while it waits.
 * If every pool thread blocks this way, the futures they wait for can never run - a classic
 * pool-starvation deadlock. On Java 21+ virtual threads make this a non-issue.
 */
public final class ConfigExecutors {

    private ConfigExecutors() {
    }

    private static final Executor EXECUTOR = createExecutor();

    private static Executor createExecutor() {
        try {
            // Java 21+: newVirtualThreadPerTaskExecutor via reflection so the bytecode stays Java 8 compatible
            return (Executor) Executors.class
                    .getMethod("newVirtualThreadPerTaskExecutor")
                    .invoke(null);
        } catch (final Exception ignored) {
            return createFallbackExecutor();
        }
    }

    /**
     * Pre-Java-21 fallback: daemon pool sized {@code max(2, cores)} with idle core-thread
     * timeout. Package-private so tests can exercise this code path on modern JVMs too.
     */
    static ThreadPoolExecutor createFallbackExecutor() {
        final int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        final AtomicInteger counter = new AtomicInteger();
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(
                threads, threads, 3L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    final Thread t = new Thread(r);
                    t.setName("everyconfig-io-" + counter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
        );
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    public static Executor get() {
        return EXECUTOR;
    }
}
