package io.ziumper.godot.lsp;

import org.openide.modules.OnStop;

/** Stops the headless Godot LSP servers when the IDE exits. */
@OnStop
public final class GodotLspShutdown implements Runnable {

    @Override
    public void run() {
        GodotLspServer.stopAll();
    }
}
