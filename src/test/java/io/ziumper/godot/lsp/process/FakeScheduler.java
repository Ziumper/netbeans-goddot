package io.ziumper.godot.lsp.process;

import java.util.ArrayList;
import java.util.List;

/** Manual scheduler: tasks run only on {@link #runPending()}, cancelled ones never. */
final class FakeScheduler implements FirstResponseWatchdog.Scheduler {

    private final List<Runnable> pending = new ArrayList<>();

    @Override
    public FirstResponseWatchdog.Cancellable schedule(Runnable task, long delayMs) {
        pending.add(task);
        return () -> pending.remove(task);
    }

    /** Runs the tasks that were scheduled and not cancelled. */
    void runPending() {
        List<Runnable> now = new ArrayList<>(pending);
        pending.clear();
        now.forEach(Runnable::run);
    }

    /** Runs the tasks even if they were cancelled meanwhile, as a racing timer thread could. */
    void runSnapshot(List<Runnable> snapshot) {
        snapshot.forEach(Runnable::run);
    }

    List<Runnable> snapshot() {
        return new ArrayList<>(pending);
    }
}
