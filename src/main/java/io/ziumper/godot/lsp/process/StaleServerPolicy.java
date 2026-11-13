package io.ziumper.godot.lsp.process;

import java.io.File;
import java.util.Locale;
import java.util.Optional;
import java.util.function.LongFunction;

/** Decides whether the process holding a project's port is a leftover headless Godot that we may kill. */
public final class StaleServerPolicy {

    /** Outcome: {@link #process()} is set for KILL only. */
    public record Decision(boolean kill, GodotProcess process, String reason) {

        public static Decision kill(GodotProcess process, String reason) {
            return new Decision(true, process, reason);
        }

        public static Decision leaveAlone(String reason) {
            return new Decision(false, null, reason);
        }
    }

    private StaleServerPolicy() {
    }

    public static Decision decide(Optional<PidFile> pidFile, File projectDir, int port,
            LongFunction<Optional<GodotProcess>> lookup) {
        if (pidFile.isEmpty()) {
            return Decision.leaveAlone("no PID file");
        }
        PidFile f = pidFile.get();
        if (!f.matchesProject(projectDir)) {
            return Decision.leaveAlone("PID file belongs to another project");
        }
        if (f.port() != port) {
            return Decision.leaveAlone("PID file is for another port");
        }
        Optional<GodotProcess> found = lookup.apply(f.pid());
        if (found.isEmpty() || !found.get().isAlive()) {
            return Decision.leaveAlone("process " + f.pid() + " is not running");
        }
        GodotProcess p = found.get();
        Optional<String> cmd = p.command();
        if (cmd.isPresent() && !cmd.get().toLowerCase(Locale.ROOT).contains("godot")) {
            return Decision.leaveAlone("process " + f.pid() + " is not Godot: " + cmd.get());
        }
        return Decision.kill(p, "matching PID file, process " + f.pid() + " is alive");
    }
}
