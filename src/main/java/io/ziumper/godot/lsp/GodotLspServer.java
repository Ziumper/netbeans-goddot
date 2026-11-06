package io.ziumper.godot.lsp;

import io.ziumper.godot.engine.GodotExecutables;
import io.ziumper.godot.engine.GodotVersion;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javax.swing.SwingUtilities;

/**
 * Runs the Godot LSP headless, one instance per project, without the Godot editor window.
 * Each project gets its own port, remembered in preferences. The processes are stopped when the
 * project is closed ({@link #stop(File)}) and when the IDE exits ({@link #stopAll()}).
 */
public final class GodotLspServer {

    private static final Logger LOG = Logger.getLogger(GodotLspServer.class.getName());
    /** Start of the port range for project servers; the editor's default 6005 stays free. */
    private static final int FIRST_PORT = 6010;
    private static final int PORT_RANGE = 1000;
    private static final int READY_TIMEOUT_MS = 30_000;
    /** A project whose start failed is not started again for this long (unless the executable changes). */
    private static final long RESTART_BACKOFF_MS = 30_000;
    private static final long STOP_WAIT_SECONDS = 3;
    private static final String PORT_KEY_PREFIX = "port.";
    /** {@code --lsp-port} first appeared in Godot 4.2. */
    private static final int MIN_MAJOR = 4;
    private static final int MIN_MINOR = 2;

    /** Running server per project directory. */
    private static final Map<String, Process> RUNNING = new ConcurrentHashMap<>();
    /** One lock per project, so waiting for one project's server does not block the others. */
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();
    /** Last failed start per project. */
    private static final Map<String, Failure> FAILURES = new ConcurrentHashMap<>();
    /** Detected major.minor per executable; only successful detections are cached. */
    private static final Map<String, String> VERSIONS = new ConcurrentHashMap<>();
    /** Guards port assignment (read-modify-write of the preferences). */
    private static final Object PORT_LOCK = new Object();
    private static final AtomicBoolean HOOK_INSTALLED = new AtomicBoolean();

    private GodotLspServer() {
    }

    private static final class Failure {
        final String exe;
        final String message;
        final long time = System.nanoTime();

        Failure(String exe, String message) {
            this.exe = exe;
            this.message = message;
        }
    }

    /**
     * Makes sure the headless LSP of the project listens on its port. Blocks for up to 30 s while the
     * server starts, so it must not be called on the EDT.
     *
     * @param exe        Godot executable to run
     * @param projectDir project directory (contains project.godot)
     * @return the port the server listens on
     * @throws IOException with a user-presentable reason if the server could not be started
     */
    public static int ensureRunning(String exe, File projectDir) throws IOException {
        if (SwingUtilities.isEventDispatchThread()) {
            LOG.log(Level.WARNING, "ensureRunning called on the EDT; this can block the UI for up to "
                    + READY_TIMEOUT_MS / 1000 + " s", new Throwable());
        }
        String key = projectDir.getAbsolutePath();
        synchronized (LOCKS.computeIfAbsent(key, k -> new Object())) {
            Failure failure = FAILURES.get(key);
            if (failure != null && failure.exe.equals(exe)
                    && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - failure.time) < RESTART_BACKOFF_MS) {
                throw new IOException(failure.message);
            }
            try {
                int port = doEnsureRunning(exe, projectDir, key);
                FAILURES.remove(key);
                return port;
            } catch (IOException ex) {
                FAILURES.put(key, new Failure(exe, ex.getMessage()));
                throw ex;
            }
        }
    }

    private static int doEnsureRunning(String exe, File projectDir, String key) throws IOException {
        if (exe == null || !GodotExecutables.isExecutable(exe)) {
            throw new IOException("Godot executable not found or not executable: " + exe);
        }
        checkVersion(exe);
        int port = portFor(projectDir);
        Process ours = RUNNING.get(key);
        if (ours != null && !ours.isAlive()) {
            RUNNING.remove(key, ours);
            ours = null;
        }
        if (ours == null) {
            if (isOpen(port)) {
                // Somebody else listens on our port (an editor, a leftover server): never talk to a foreign
                // server, pick another port instead.
                int old = port;
                port = reassignPort(projectDir);
                LOG.log(Level.INFO, "Port {0} of {1} is taken by another process, using {2}",
                        new Object[]{old, projectDir, port});
            }
            ours = start(exe, projectDir, port);
            RUNNING.put(key, ours);
            installShutdownHook();
        }
        // Either freshly started, or started by an earlier call that timed out while Godot was still importing.
        waitUntilOpen(port, ours, logFile(projectDir, port));
        return port;
    }

    private static void checkVersion(String exe) throws IOException {
        String version = VERSIONS.get(exe);
        if (version == null) {
            // Unknown version: refuse. --version prints nothing parseable only if the binary does not run
            // properly, and a start attempt would then just burn the 30 s timeout.
            Optional<String> detected = GodotVersion.detect(exe);
            if (detected.isEmpty()) {
                throw new IOException("Could not detect the Godot version of " + exe
                        + " (the headless LSP needs Godot 4.2 or newer)");
            }
            version = detected.get();
            VERSIONS.put(exe, version);
        }
        if (!isSupported(version)) {
            throw new IOException("Godot " + version + " is too old for the headless LSP (needs 4.2 or newer)");
        }
    }

    private static boolean isSupported(String majorMinor) {
        String[] parts = majorMinor.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > MIN_MAJOR || (major == MIN_MAJOR && minor >= MIN_MINOR);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static Process start(String exe, File projectDir, int port) throws IOException {
        File log = logFile(projectDir, port);
        ProcessBuilder pb = new ProcessBuilder(exe, "--headless", "--editor",
                "--path", projectDir.getAbsolutePath(), "--lsp-port", String.valueOf(port));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(log));
        try {
            Process p = pb.start();
            LOG.log(Level.INFO, "Started headless Godot LSP for {0} on port {1}, log: {2}",
                    new Object[]{projectDir, port, log});
            return p;
        } catch (IOException ex) {
            throw new IOException("Could not start " + exe + ": " + ex.getMessage(), ex);
        }
    }

    /** Log of the server of the project; overwritten on every start. */
    private static File logFile(File projectDir, int port) {
        String name = projectDir.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(System.getProperty("java.io.tmpdir"), "godot-lsp-" + name + "-" + port + ".log");
    }

    /**
     * Stops the headless server of the project, if we started one. Blocks for a few seconds, so call it
     * off the EDT.
     */
    public static void stop(File projectDir) {
        String key = projectDir.getAbsolutePath();
        synchronized (LOCKS.computeIfAbsent(key, k -> new Object())) {
            Process p = RUNNING.remove(key);
            FAILURES.remove(key);
            if (p != null) {
                LOG.log(Level.INFO, "Stopping headless Godot LSP of {0}", projectDir);
                terminate(p);
            }
        }
    }

    /** Stops all servers started by this IDE session; called when the IDE exits. */
    static void stopAll() {
        List<Process> all = new ArrayList<>(RUNNING.values());
        RUNNING.clear();
        all.forEach(Process::destroy);
        for (Process p : all) {
            terminate(p);
        }
    }

    private static void terminate(Process p) {
        p.destroy();
        try {
            if (!p.waitFor(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** Port assigned to the project; a free one is picked and stored on first use. */
    public static int portFor(File projectDir) throws IOException {
        synchronized (PORT_LOCK) {
            return assignPort(projectDir, false);
        }
    }

    private static int reassignPort(File projectDir) throws IOException {
        synchronized (PORT_LOCK) {
            return assignPort(projectDir, true);
        }
    }

    /** Must be called under PORT_LOCK. */
    private static int assignPort(File projectDir, boolean forceNew) throws IOException {
        Preferences prefs = prefs();
        String key = PORT_KEY_PREFIX + sha1(projectDir.getAbsolutePath());
        if (!forceNew) {
            int saved = prefs.getInt(key, -1);
            if (saved > 0) {
                return saved;
            }
        }
        Set<Integer> assigned = new HashSet<>();
        try {
            for (String k : prefs.keys()) {
                if (k.startsWith(PORT_KEY_PREFIX) && !k.equals(key)) {
                    assigned.add(prefs.getInt(k, -1));
                }
            }
        } catch (BackingStoreException ex) {
            LOG.log(Level.FINE, null, ex);
        }
        int port = FIRST_PORT;
        while (assigned.contains(port) || !isFree(port)) {
            if (++port >= FIRST_PORT + PORT_RANGE) {
                throw new IOException("No free port for the headless Godot LSP in "
                        + FIRST_PORT + "-" + (FIRST_PORT + PORT_RANGE - 1));
            }
        }
        prefs.putInt(key, port);
        try {
            prefs.flush();
        } catch (BackingStoreException ex) {
            LOG.log(Level.FINE, null, ex);
        }
        return port;
    }

    /** Preferences keys are limited to 80 characters, so the path is stored as a hash. */
    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex); // SHA-1 is required to exist on every JVM
        }
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

    /** Waits until the port accepts connections; fails at once, with the exit code, if our process exits first. */
    private static void waitUntilOpen(int port, Process process, File log) throws IOException {
        long end = System.currentTimeMillis() + READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < end) {
            if (!process.isAlive()) {
                throw new IOException("Godot exited with code " + process.exitValue()
                        + " before the LSP came up, see " + log);
            }
            if (isOpen(port)) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for the Godot LSP", ex);
            }
        }
        throw new IOException("Godot LSP did not open port " + port + " within " + READY_TIMEOUT_MS / 1000
                + " s, see " + log);
    }

    /**
     * Safety net for exits that do not go through the IDE's normal shutdown ({@link GodotLspShutdown}),
     * e.g. SIGTERM. Only destroys; there is no time to wait in a shutdown hook.
     */
    private static void installShutdownHook() {
        if (HOOK_INSTALLED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> RUNNING.values().forEach(Process::destroy), "godot-lsp-shutdown"));
        }
    }
}
