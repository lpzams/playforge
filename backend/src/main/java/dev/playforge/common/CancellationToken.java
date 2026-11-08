package dev.playforge.common;

import java.util.ArrayList;
import java.util.List;

/** Cooperative cancellation signal shared by a run and everything it calls. */
public final class CancellationToken {

    private boolean cancelled;
    private final List<Runnable> callbacks = new ArrayList<>();

    public void cancel() {
        List<Runnable> toRun;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            toRun = List.copyOf(callbacks);
            callbacks.clear();
        }
        toRun.forEach(Runnable::run);
    }

    public synchronized boolean isCancelled() {
        return cancelled;
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
