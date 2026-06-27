package br.com.finalcraft.everyconfig.io;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** A shared daemon thread pool for asynchronous config saves, so a save never blocks a caller's thread. */
public final class ConfigExecutor {

    private static final ExecutorService SHARED =
            Executors.newCachedThreadPool(new ThreadFactory() {
                private final AtomicInteger n = new AtomicInteger();

                @Override
                public Thread newThread(final Runnable r) {
                    final Thread t = new Thread(r, "finalconfig-io-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    private ConfigExecutor() {
    }

    public static ExecutorService shared() {
        return SHARED;
    }
}
