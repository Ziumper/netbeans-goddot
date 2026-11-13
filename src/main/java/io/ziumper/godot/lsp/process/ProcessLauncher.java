package io.ziumper.godot.lsp.process;

import io.ziumper.godot.engine.GodotExecutables;
import java.io.File;
import java.io.IOException;

/** Starts the headless Godot of a project. */
public interface ProcessLauncher {

    /** True if {@code exe} is an executable file, either as a path or found on PATH. */
    boolean isExecutable(String exe);

    /** Runs {@code exe --headless --editor --path <projectDir> --lsp-port <port>}, all output to {@code log}. */
    GodotProcess launch(String exe, File projectDir, int port, File log) throws IOException;

    /** A child process of the IDE. */
    ProcessLauncher HEADLESS_GODOT = new ProcessLauncher() {
        @Override
        public boolean isExecutable(String exe) {
            return GodotExecutables.isExecutable(exe);
        }

        @Override
        public GodotProcess launch(String exe, File projectDir, int port, File log) throws IOException {
            ProcessBuilder pb = new ProcessBuilder(exe, "--headless", "--editor",
                    "--path", projectDir.getAbsolutePath(), "--lsp-port", String.valueOf(port));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(log));
            return new JavaProcess(pb.start());
        }
    };
}
