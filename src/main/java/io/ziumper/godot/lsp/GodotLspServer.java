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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javax.swing.SwingUtilities;
import org.openide.util.RequestProcessor;

/**
 * Runs the Godot LSP headless, one instance per project, without the Godot editor window.
 * Each project gets its own port, remembered in preferences. The processes are stopped when the
 * project is closed ({@link #close(File)}) and when the IDE exits ({@link #stopAll()}).
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
    /**
     * Projects closed on purpose; no server is started for them until reopened. Set by {@link #close(File)}
     * and cleared by {@link #allow(File)}, which are called only from the project's open/close hooks.
     */
    private static final Set<String> STOPPED = ConcurrentHashMap.newKeySet();
    /**
     * Session counter per project, bumped on every close. A start remembers the value it began with and
     * gives up (killing its own process) if it changed, so a start from a closed session never survives,
     * even if the project was reopened meanwhile.
     */
    private static final Map<String, AtomicLong> GENERATIONS = new ConcurrentHashMap<>();
    /** Processes detached from RUNNING by close() that are still being killed; stopAll() must not miss them. */
    private static final Set<Process> DETACHED = ConcurrentHashMap.newKeySet();
    /** Kills the processes of closed projects off the hook's thread. */
    private static final RequestProcessor RP = new RequestProcessor(GodotLspServer.class);
    /** Set once the IDE is exiting; nothing is started after that. */
    private static final AtomicBoolean SHUTTING_DOWN = new AtomicBoolean();

    private GodotLspServer() {
    }

    /** The start was refused on purpose (IDE exit, closed project); not a failure to report or retry. */
    public static final class StartRefusedException extends IOException {
        StartRefusedException(String message) {
            super(message);
        }
    }

    /** True once the IDE has begun exiting; the servers are being stopped and must not come back. */
    public static boolean isShuttingDown() {
        return SHUTTING_DOWN.get();
    }

    /** True if no server should run for the project: the IDE is exiting or the project was closed. */
    public static boolean isStoppedIntentionally(File projectDir) {
        return isShuttingDown() || (projectDir != null && STOPPED.contains(projectDir.getAbsolutePath()));
    }

    private static AtomicLong counter(String key) {
        return GENERATIONS.computeIfAbsent(key, k -> new AtomicLong());
    }

    /** Session counter of the project; changes whenever the project is closed. */
    static long generation(File projectDir) {
        return counter(keyOf(projectDir)).get();
    }

    static String keyOf(File projectDir) {
        return projectDir.getAbsolutePath();
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
     * @throws StartRefusedException if the IDE is exiting or the project was closed
     * @throws IOException with a user-presentable reason if the server could not be started
     */
    public static int ensureRunning(String exe, File projectDir) throws IOException {
        if (SwingUtilities.isEventDispatchThread()) {
            LOG.log(Level.WARNING, "ensureRunning called on the EDT; this can block the UI for up to "
                    + READY_TIMEOUT_MS / 1000 + " s", new Throwable());
        }
        String key = keyOf(projectDir);
        // Taken before the first check: a close after this point is always noticed by checkNotStopped.
        long generation = counter(key).get();
        checkNotStopped(key, generation);
        synchronized (LOCKS.computeIfAbsent(key, k -> new Object())) {
            // Checked again: close() may have run while we waited for the lock.
            checkNotStopped(key, generation);
            Failure failure = FAILURES.get(key);
            if (failure != null && failure.exe.equals(exe)
                    && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - failure.time) < RESTART_BACKOFF_MS) {
                throw new IOException(failure.message);
            }
            try {
                int port = doEnsureRunning(exe, projectDir, key, generation);
                FAILURES.remove(key);
                return port;
            } catch (StartRefusedException ex) {
                throw ex; // not a failure of the server, so no backoff
            } catch (IOException ex) {
                FAILURES.put(key, new Failure(exe, ex.getMessage()));
                throw ex;
            }
        }
    }

    /**
     * Refuses to start during IDE shutdown, for a project closed on purpose and for a start that began in an
     * earlier session of the project. Only the project's hooks lift the mark ({@link #allow(File)}).
     */
    private static void checkNotStopped(String key, long generation) throws StartRefusedException {
        if (SHUTTING_DOWN.get()) {
            throw new StartRefusedException("IDE is shutting down");
        }
        if (STOPPED.contains(key) || counter(key).get() != generation) {
            throw new StartRefusedException("project closed");
        }
    }

    private static int doEnsureRunning(String exe, File projectDir, String key, long generation) throws IOException {
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
            if (SHUTTING_DOWN.get() || STOPPED.contains(key) || counter(key).get() != generation) {
                // stopAll() or close() ran between our check and the put and did not see this process:
                // it is ours, so we kill it. (If close() did see it, killing twice is harmless.)
                RUNNING.remove(key, ours);
                terminate(ours);
                throw new StartRefusedException(SHUTTING_DOWN.get() ? "IDE is shutting down" : "project closed");
            }
        }
        // Either freshly started, or started by an earlier call that timed out while Godot was still importing.
        waitUntilOpen(port, ours, logFile(projectDir, port), key, generation);
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
     * Called from the project's close hook, possibly on the EDT: never blocks and takes no project lock.
     * Marks the project closed, ends the current session and detaches the server of that session from
     * RUNNING; the detached process (this very object, so never a server started after a reopen) is killed
     * on a background thread. A start in progress notices the mark and kills its own process.
     */
    public static void close(File projectDir) {
        String key = keyOf(projectDir);
        STOPPED.add(key);
        counter(key).incrementAndGet();
        FAILURES.remove(key);
        Process p = RUNNING.remove(key);
        if (p != null) {
            DETACHED.add(p);
            RP.post(() -> {
                try {
                    LOG.log(Level.INFO, "Stopping headless Godot LSP of {0}", projectDir);
                    terminate(p);
                } finally {
                    DETACHED.remove(p);
                }
            });
        }
    }

    /**
     * Called from the project's open hook: lifts the mark set by {@link #close(File)}. Does nothing for a
     * project that was not marked (e.g. projects opened at IDE startup). Cheap, never blocks.
     */
    public static void allow(File projectDir) {
        String key = keyOf(projectDir);
        STOPPED.remove(key);
        FAILURES.remove(key);
    }

    /**
     * Stops all servers started by this IDE session; called when the IDE exits. Blocks for at most
     * {@link #STOP_WAIT_SECONDS} in total, however many servers there are.
     */
    static void stopAll() {
        SHUTTING_DOWN.set(true);
        List<Process> all = new ArrayList<>(RUNNING.values());
        all.addAll(DETACHED);
        RUNNING.clear();
        terminateAll(all);
    }

    private static void terminate(Process p) {
        terminateAll(List.of(p));
    }

    /**
     * Stops the processes together with all their descendants (the executable may be a wrapper script,
     * flatpak or snap, with the real Godot as a child): SIGTERM to every tree, one shared wait, then
     * a forced kill of whatever is still alive.
     */
    private static void terminateAll(Collection<Process> processes) {
        List<ProcessHandle> handles = destroyTrees(processes);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STOP_WAIT_SECONDS);
        try {
            for (ProcessHandle h : handles) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                try {
                    h.onExit().get(left, TimeUnit.NANOSECONDS);
                } catch (TimeoutException ex) {
                    break; // the deadline is shared, so the rest has no time left either
                } catch (ExecutionException ex) {
                    LOG.log(Level.FINE, null, ex);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        for (ProcessHandle h : handles) {
            if (h.isAlive()) {
                h.destroyForcibly();
            }
        }
    }

    /**
     * Sends SIGTERM to the processes and their descendants and returns all handles. The descendants are
     * collected first: once the parent is gone they are reparented and can no longer be found through it.
     */
    private static List<ProcessHandle> destroyTrees(Collection<Process> processes) {
        List<ProcessHandle> handles = new ArrayList<>();
        for (Process p : processes) {
            ProcessHandle parent = p.toHandle();
            parent.descendants().forEach(handles::add);
            handles.add(parent);
        }
        handles.forEach(ProcessHandle::destroy);
        return handles;
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
    private static void waitUntilOpen(int port, Process process, File log, String key, long generation)
            throws IOException {
        long end = System.currentTimeMillis() + READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < end) {
            // close() marks the project without waiting for our lock; give up so we do not wait out the timeout.
            // The process was detached from RUNNING by close(), which kills it, so there is nothing to clean up.
            if (SHUTTING_DOWN.get() || STOPPED.contains(key) || counter(key).get() != generation) {
                throw new StartRefusedException(SHUTTING_DOWN.get() ? "IDE is shutting down" : "project closed");
            }
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
     * e.g. SIGTERM. Only destroys (the whole process trees); there is no time to wait in a shutdown hook.
     */
    private static void installShutdownHook() {
        if (HOOK_INSTALLED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> {
                        List<Process> all = new ArrayList<>(RUNNING.values());
                        all.addAll(DETACHED);
                        destroyTrees(all);
                    }, "godot-lsp-shutdown"));
        }
    }
}
