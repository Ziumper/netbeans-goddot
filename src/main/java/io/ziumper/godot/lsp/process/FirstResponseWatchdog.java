package io.ziumper.godot.lsp.process;

/**
 * Fires a callback if nothing arrives within a timeout after {@link #arm}. Idempotent and thread-safe: once
 * disarmed (first byte or {@link #disarm()}), a late or racing timeout never runs the callback.
 */
public final class FirstResponseWatchdog {

    /** Cancels a scheduled task. */
    public interface Cancellable {
        void cancel();
    }

    /** Runs a task once after a delay. */
    public interface Scheduler {
        Cancellable schedule(Runnable task, long delayMs);
    }

    private final Scheduler scheduler;
    private final Object lock = new Object();
    private Object token;
    private Cancellable pending;

    public FirstResponseWatchdog(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public void arm(long timeoutMs, Runnable onTimeout) {
        synchronized (lock) {
            disarmLocked();
            Object mine = new Object();
            token = mine;
            pending = scheduler.schedule(() -> {
                synchronized (lock) {
                    if (token != mine) {
                        return;
                    }
                    token = null;
                    pending = null;
                }
                onTimeout.run();
            }, timeoutMs);
        }
    }

    /** The first byte arrived: the timeout can no longer fire. */
    public void onFirstByte() {
        disarm();
    }

    public void disarm() {
        synchronized (lock) {
            disarmLocked();
        }
    }

    private void disarmLocked() {
        token = null;
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
    }
}
