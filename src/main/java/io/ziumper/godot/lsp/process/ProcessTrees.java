package io.ziumper.godot.lsp.process;

import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Common "tree, shared time limit, then force" shutdown for any {@link GodotProcess}. */
public final class ProcessTrees {

    private static final Logger LOG = Logger.getLogger(ProcessTrees.class.getName());

    private ProcessTrees() {
    }

    /** Gently stops all trees, without waiting. */
    public static void destroyAll(Collection<? extends GodotProcess> processes) {
        processes.forEach(GodotProcess::destroy);
    }

    /**
     * Gently stops all trees, waits for them in total at most {@code limitNanos}, then forcibly kills whatever
     * is still alive.
     */
    public static void terminateAll(Collection<? extends GodotProcess> processes, long limitNanos) {
        destroyAll(processes);
        long deadline = System.nanoTime() + limitNanos;
        try {
            for (GodotProcess p : processes) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                try {
                    p.onExit().get(left, TimeUnit.NANOSECONDS);
                } catch (TimeoutException ex) {
                    break; // the deadline is shared, so the rest has no time left either
                } catch (ExecutionException ex) {
                    LOG.log(Level.FINE, null, ex);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        for (GodotProcess p : processes) {
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}
