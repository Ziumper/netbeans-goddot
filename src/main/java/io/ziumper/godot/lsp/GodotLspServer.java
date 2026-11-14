package io.ziumper.godot.lsp;

import io.ziumper.godot.lsp.process.HeadlessServer;
import io.ziumper.godot.lsp.process.PortStore;
import io.ziumper.godot.lsp.process.ServerRegistry;
import io.ziumper.godot.lsp.process.StartRefusedException;
import io.ziumper.godot.lsp.process.StopReason;

import java.io.File;
import java.io.IOException;
import java.util.prefs.Preferences;

/**
 * Runs the Godot LSP headless, one instance per project, without the Godot editor window. Static entry point for
 * the project hooks, the options and the LSP client; the work is done by {@link HeadlessServer} instances of one
 * {@link ServerRegistry}. The processes are stopped when the project is closed ({@link #close(File)}) and when the
 * IDE exits ({@link #stopAll()}).
 */
public final class GodotLspServer {

    /** Ports stay in the node they always used, so projects keep their ports across this refactoring. */
    private static final ServerRegistry REGISTRY = ServerRegistry.createDefault(
            PortStore.preferences(() -> Preferences.userNodeForPackage(GodotLspServer.class)));

    private GodotLspServer() {
    }

    /** True once the IDE has begun exiting; the servers are being stopped and must not come back. */
    public static boolean isShuttingDown() {
        return REGISTRY.isShuttingDown();
    }

    /** True if no server should run for the project: the IDE is exiting or the project was closed. */
    public static boolean isStoppedIntentionally(File projectDir) {
        return projectDir == null ? REGISTRY.isShuttingDown() : server(projectDir).isStoppedIntentionally();
    }

    /** Session counter of the project; changes whenever the project is closed or its server stopped for settings. */
    static long generation(File projectDir) {
        return server(projectDir).generation();
    }

    static String keyOf(File projectDir) {
        return ServerRegistry.keyOf(projectDir);
    }

    /**
     * Makes sure the headless LSP of the project listens on its port; see {@link HeadlessServer#ensureReady}.
     * Blocks while Godot starts, so never call it on the EDT or from lsp.client's startServer.
     *
     * @throws StartRefusedException if the IDE is exiting or the project was closed
     * @throws IOException with a user-presentable reason if the server could not be started
     */
    public static int ensureRunning(String exe, File projectDir) throws IOException {
        return server(projectDir).ensureReady(exe);
    }

    /** Port of the project's server if it is up, -1 if it must be started first; never blocks. */
    public static int readyPort(File projectDir) {
        return server(projectDir).readyPort();
    }

    /** Project close hook; never blocks. See {@link HeadlessServer#close()}. */
    public static void close(File projectDir) {
        server(projectDir).close();
    }

    /** Project open hook; never blocks. See {@link HeadlessServer#allow()}. */
    public static void allow(File projectDir) {
        server(projectDir).allow();
    }

    /** Stops all servers started by this IDE session; called when the IDE exits. Blocks for about 3 s at most. */
    static void stopAll() {
        stopAll(StopReason.IDE_EXIT);
    }

    /** See {@link ServerRegistry#stopAll(StopReason)}. */
    public static void stopAll(StopReason reason) {
        REGISTRY.stopAll(reason);
    }

    /** Port assigned to the project; a free one is picked and stored on first use. */
    public static int portFor(File projectDir) throws IOException {
        return REGISTRY.portFor(projectDir);
    }

    private static HeadlessServer server(File projectDir) {
        return REGISTRY.forProject(projectDir);
    }
}
