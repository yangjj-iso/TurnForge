package io.github.yangjjiso.redyagents.core;

import java.util.concurrent.CancellationException;

public final class CancellationToken {
    private enum StopReason { CANCELLED, TIMED_OUT }
    private volatile StopReason reason;
    private Thread worker;

    public boolean isCancelled() {
        return reason != null;
    }

    public boolean isTimedOut() {
        return reason == StopReason.TIMED_OUT;
    }

    public void throwIfCancelled() {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("turn cancelled");
        }
    }

    synchronized void attachWorker() {
        Thread current = Thread.currentThread();
        worker = current;
        if (isCancelled()) {
            current.interrupt();
        }
    }

    synchronized void detachWorker() {
        if (worker == Thread.currentThread()) {
            worker = null;
        }
    }

    void cancel() {
        stop(StopReason.CANCELLED);
    }

    void timeout() {
        stop(StopReason.TIMED_OUT);
    }

    private synchronized void stop(StopReason requested) {
        if (reason == null) {
            reason = requested;
        }
        if (worker != null) {
            worker.interrupt();
        }
    }
}
