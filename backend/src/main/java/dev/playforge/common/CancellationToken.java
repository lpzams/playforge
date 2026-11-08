package dev.playforge.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Cooperative cancellation signal shared by a run and everything it calls.
 * {@link #cancel()} = the user stopped the run; {@link #shutdown()} = the process is stopping and the run
 * must stay resumable. Both make {@link #isCancelled()} true; {@link #isShutdown()} tells them apart.
 */
public final class CancellationToken {

    private boolean cancelled;
    private boolean shutdown;
    private final List<Runnable> callbacks = new ArrayList<>();

    public void cancel() {
        trigger(false);
    }

    public void shutdown() {
        trigger(true);
    }

    private void trigger(boolean isShutdown) {
        List<Runnable> toRun;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            shutdown = isShutdown;
            toRun = List.copyOf(callbacks);
            callbacks.clear();
        }
        toRun.forEach(Runnable::run);
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    public synchronized boolean isShutdown() {
        return shutdown;
    }

    /** Registers a callback; runs it immediately if already cancelled. */
    public void onCancel(Runnable r) {
        synchronized (this) {
            if (!cancelled) {
                callbacks.add(r);
                return;
            }
        }
        r.run();
    }

    public void throwIfCancelled() {
        if (isCancelled()) throw new CancelledException();
    }
}
