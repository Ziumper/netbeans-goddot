package io.ziumper.godot.lsp.process;

/** A process adopted by PID (e.g. a leftover of an earlier IDE session). */
public final class HandleProcess extends TreeProcess {

    public HandleProcess(ProcessHandle handle) {
        super(handle);
    }
}
