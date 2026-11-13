package io.ziumper.godot.lsp.process;

import org.openide.util.RequestProcessor;

/** {@link FirstResponseWatchdog.Scheduler} on a NetBeans RequestProcessor. */
public final class RequestProcessorScheduler implements FirstResponseWatchdog.Scheduler {

    private final RequestProcessor rp;

    public RequestProcessorScheduler(RequestProcessor rp) {
        this.rp = rp;
    }

    @Override
    public FirstResponseWatchdog.Cancellable schedule(Runnable task, long delayMs) {
        RequestProcessor.Task t = rp.post(task, (int) Math.min(Integer.MAX_VALUE, delayMs));
        return t::cancel;
    }
}
