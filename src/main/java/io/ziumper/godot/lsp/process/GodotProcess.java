package io.ziumper.godot.lsp.process;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

/** A Godot process, either started by this IDE or adopted by PID. Destroying acts on the whole process tree. */
public interface GodotProcess {

    long pid();

    boolean isAlive();

    /** Gently (SIGTERM) stops the whole tree, descendants first. */
    void destroy();

    /** Forcibly kills the whole tree. */
    void destroyForcibly();

    /** Completes when the process (and, after {@link #destroy()}, its tree) has exited. */
    CompletableFuture<?> onExit();

    /** Executable name or path, if the OS reports it. */
    Optional<String> command();

    /** Exit code, if known (only for processes started by us). */
    default OptionalInt exitCode() {
        return OptionalInt.empty();
    }
}
