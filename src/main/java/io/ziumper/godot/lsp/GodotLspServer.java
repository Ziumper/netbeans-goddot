package io.ziumper.godot.lsp;

import io.ziumper.godot.engine.GodotExecutables;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Runs the Godot LSP headless, one instance per project, without the Godot editor window.
 * Each project gets its own port, remembered in preferences. The processes are stopped when the IDE exits.
 */
public final class GodotLspServer {

    private static final Logger LOG = Logger.getLogger(GodotLspServer.class.getName());
    /** Start of the port range for project servers; the editor's default 6005 stays free. */
    private static final int FIRST_PORT = 6010;
    private static final int READY_TIMEOUT_MS = 30_000;
    private static final String PORT_KEY_PREFIX = "port.";

    /** Running server per project directory. */
    private static final Map<String, Process> RUNNING = new ConcurrentHashMap<>();
    private static boolean hookInstalled;

    private GodotLspServer() {
    }

    /**
     * Makes sure the headless LSP of the project listens on its port.
     *
     * @param exe        Godot executable to run
     * @param projectDir project directory (contains project.godot)
     * @return the port, or -1 if the Godot executable is not usable or the server did not come up
     */
    public static synchronized int ensureRunning(String exe, File projectDir) {
        if (exe == null || !GodotExecutables.isExecutable(exe)) {
            return -1;
        }
        int port = portFor(projectDir);
        if (isOpen(port)) {
            return port; // already listening: ours from earlier, or a leftover server
        }
        String key = projectDir.getAbsolutePath();
        Process running = RUNNING.get(key);
        if (running == null || !running.isAlive()) {
            if (!start(exe, projectDir, port)) {
                return -1;
            }
        }
        return waitUntilOpen(port, RUNNING.get(key)) ? port : -1;
    }

    private static boolean start(String exe, File projectDir, int port) {
        ProcessBuilder pb = new ProcessBuilder(exe, "--headless", "--editor",
                "--path", projectDir.getAbsolutePath(), "--lsp-port", String.valueOf(port));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(
                new File(System.getProperty("java.io.tmpdir"), "godot-lsp-" + port + ".log")));
        try {
            Process p = pb.start();
            RUNNING.put(projectDir.getAbsolutePath(), p);
            LOG.log(Level.INFO, "Started headless Godot LSP for {0} on port {1}", new Object[]{projectDir, port});
            installShutdownHook();
            return true;
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Could not start headless Godot LSP: " + exe, ex);
            return false;
        }
    }

    /** Port assigned to the project; a free one is picked and stored on first use. */
    public static int portFor(File projectDir) {
        Preferences prefs = prefs();
        String key = PORT_KEY_PREFIX + Integer.toHexString(projectDir.getAbsolutePath().hashCode());
        int saved = prefs.getInt(key, -1);
        if (saved > 0) {
            return saved;
        }
        Set<Integer> assigned = new HashSet<>();
        try {
            for (String k : prefs.keys()) {
                if (k.startsWith(PORT_KEY_PREFIX)) {
                    assigned.add(prefs.getInt(k, -1));
                }
            }
        } catch (BackingStoreException ex) {
            LOG.log(Level.FINE, null, ex);
        }
        int port = FIRST_PORT;
        while (assigned.contains(port) || !isFree(port)) {
            port++;
        }
        prefs.putInt(key, port);
        try {
            prefs.flush();
        } catch (BackingStoreException ex) {
            LOG.log(Level.FINE, null, ex);
        }
        return port;
    }

    private static Preferences prefs() {
        return Preferences.userNodeForPackage(GodotLspServer.class);
    }

    private static boolean isOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean isFree(int port) {
        try (ServerSocket ss = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    /** Waits until the port accepts connections, giving up if the Godot process exits first. */
    private static boolean waitUntilOpen(int port, Process process) {
        long end = System.currentTimeMillis() + READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < end) {
            if (isOpen(port)) {
                return true;
            }
            if (process == null || !process.isAlive()) {
                return false;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void installShutdownHook() {
        if (!hookInstalled) {
            hookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> RUNNING.values().forEach(Process::destroy), "godot-lsp-shutdown"));
        }
    }
}
