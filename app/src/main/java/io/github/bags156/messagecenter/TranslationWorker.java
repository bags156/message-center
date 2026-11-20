package io.github.bags156.messagecenter;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one thread every translation runs on (neither engine is safe to use from two threads), where
 * something you're waiting on goes first: a message you're sending, or one you asked to translate,
 * runs ahead of everything queued, and while it waits, translations of received messages that are
 * already running stop (they're tried again after). Otherwise, first come, first served.
 * Pure Java, for unit tests.
 */
final class TranslationWorker extends ThreadPoolExecutor {
    private static final AtomicLong order = new AtomicLong();
    private final AtomicInteger urgentWaiting = new AtomicInteger();

    TranslationWorker(String name) {
        super(1, 1, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(), r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; });
    }

    /** A queued job: urgent ones first, then in the order they came. */
    private static final class Job<T> extends FutureTask<T> implements Comparable<Job<?>> {
        final boolean urgent; final long seq = order.incrementAndGet();
        Job(Callable<T> c, boolean urgent) { super(c); this.urgent = urgent; }
        Job(Runnable r, T value, boolean urgent) { super(r, value); this.urgent = urgent; }
        @Override public int compareTo(Job<?> o) { if (urgent != o.urgent) return urgent ? -1 : 1; return Long.compare(seq, o.seq); }
    }

    @Override protected <T> RunnableFuture<T> newTaskFor(Callable<T> c) { return new Job<>(c, false); }
    @Override protected <T> RunnableFuture<T> newTaskFor(Runnable r, T value) { return new Job<>(r, value, false); }
    @Override public void execute(Runnable r) { super.execute(r instanceof Job ? r : new Job<Void>(r, null, false)); }

    /** Something you're waiting on: ahead of everything queued; received messages' translations stop to let it through. */
    void urgent(Runnable r) {
        urgentWaiting.incrementAndGet();
        super.execute(new Job<Void>(() -> { try { r.run(); } finally { urgentWaiting.decrementAndGet(); } }, null, true));
    }

    /** Whether something you're waiting on is queued or running: background translations should stop. */
    boolean urgentWaiting() { return urgentWaiting.get() > 0; }
}
