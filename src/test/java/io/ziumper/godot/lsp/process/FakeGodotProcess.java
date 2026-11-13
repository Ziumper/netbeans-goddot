package io.ziumper.godot.lsp.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

/** Hand-driven process: tests decide whether it is alive and see what was done to it. */
final class FakeGodotProcess implements GodotProcess {

    final List<String> calls = new ArrayList<>();
    private final long pid;
    private final String command;
    boolean alive = true;
    /** True if {@link #destroy()} makes the process exit (a well-behaved one). */
    boolean diesOnDestroy = true;
    /** Exit code reported once the process is dead; null if unknown. */
    Integer exitCode;
    private final CompletableFuture<Void> exit = new CompletableFuture<>();

    FakeGodotProcess(long pid, String command) {
        this.pid = pid;
        this.command = command;
    }

    @Override
    public long pid() {
        return pid;
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public void destroy() {
        calls.add("destroy");
        if (diesOnDestroy) {
            die();
        }
    }

    @Override
    public void destroyForcibly() {
        calls.add("destroyForcibly");
        die();
    }

    void die() {
        alive = false;
        exit.complete(null);
    }

    @Override
    public CompletableFuture<?> onExit() {
        return exit;
    }

    @Override
    public Optional<String> command() {
        return Optional.ofNullable(command);
    }

    @Override
    public OptionalInt exitCode() {
        return alive || exitCode == null ? OptionalInt.empty() : OptionalInt.of(exitCode);
    }
}
