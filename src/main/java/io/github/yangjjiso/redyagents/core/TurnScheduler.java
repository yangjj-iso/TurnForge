package io.github.yangjjiso.redyagents.core;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns bounded workers and execution deadlines. Waiting for external input consumes no worker. */
final class TurnScheduler implements AutoCloseable {
    private final ExecutorService workers;
    private final ScheduledThreadPoolExecutor deadlines;
    private final long timeoutMillis;

    TurnScheduler(RuntimeLimits limits) {
        BlockingQueue<Runnable> queue = limits.maxQueuedTurns() == 0
                ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(limits.maxQueuedTurns());
        workers = new ThreadPoolExecutor(limits.maxConcurrentTurns(), limits.maxConcurrentTurns(),
                0L, TimeUnit.MILLISECONDS, queue, threads("turnforge-turn-"),
                new ThreadPoolExecutor.AbortPolicy());
        deadlines = new ScheduledThreadPoolExecutor(1, threads("turnforge-deadline-"));
        deadlines.setRemoveOnCancelPolicy(true);
        timeoutMillis = limits.turnTimeout().toMillis();
    }

    void execute(CancellationToken cancellation, Runnable work) {
        workers.execute(() -> {
            var deadline = deadlines.schedule(cancellation::timeout, timeoutMillis, TimeUnit.MILLISECONDS);
            try {
                work.run();
            } finally {
                deadline.cancel(false);
            }
        });
    }

    static ThreadFactory threads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void close() {
        workers.shutdownNow();
        deadlines.shutdownNow();
    }
}
