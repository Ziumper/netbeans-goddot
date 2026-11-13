package io.ziumper.godot.lsp.process;

import java.util.OptionalInt;

/** A process started by this IDE. */
public final class JavaProcess extends TreeProcess {

    private final Process process;

    public JavaProcess(Process process) {
        super(process.toHandle());
        this.process = process;
    }

    @Override
    public OptionalInt exitCode() {
        return process.isAlive() ? OptionalInt.empty() : OptionalInt.of(process.exitValue());
    }
}
