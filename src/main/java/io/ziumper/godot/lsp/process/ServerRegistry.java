package io.ziumper.godot.lsp.process;

import io.ziumper.godot.engine.GodotVersion;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openide.util.RequestProcessor;

/**
 * The headless Godot LSP servers of this IDE session, one {@link HeadlessServer} per project directory, and the
 * only state they share: the IDE-exit flag, the JVM shutdown hook, the port assignment and the version cache.
 * Everything that touches the OS or the clock comes in through the constructor.
 */
public final class ServerRegistry {

    private static final Logger LOG = Logger.getLogger(ServerRegistry.class.getName());
    /** Start of the port range for project servers; the editor's default 6005 stays free. */
    static final int FIRST_PORT = 6010;
    static final int PORT_RANGE = 1000;

    /** Time limits in milliseconds. */
    public record Limits(long readyTimeoutMs, long restartBackoffMs, long stopWaitMs, long readyPollMs,
            long exitPollMs) {

        /**
         * 30 s for the port to open, 30 s before a failed start is tried again with the same executable, 3 s for
         * the processes to stop, port polled every 250 ms while starting and every 100 ms while a server exits.
         */
        public static final Limits DEFAULT = new Limits(30_000, 30_000, 3_000, 250, 100);
    }

    final ProcessLauncher launcher;
    final PortProbe probe;
    private final PortStore ports;
    private final VersionDetector versionDetector;
    final LongFunction<Optional<GodotProcess>> processLookup;
    private final FirstResponseWatchdog.Scheduler scheduler;
    final Ticker ticker;
    final Path tmpDir;
    private final Consumer<Thread> shutdownHooks;
    final Limits limits;

    private final Map<String, HeadlessServer> servers = new ConcurrentHashMap<>();
    /** Detected major.minor per executable; only successful detections are cached. */
    private final Map<String, String> versions = new ConcurrentHashMap<>();
    /** Guards port assignment (read-modify-write of the store). */
    private final Object portLock = new Object();
    private final AtomicBoolean hookInstalled = new AtomicBoolean();
    /** Set once the IDE is exiting; nothing is started after that. */
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    /**
     * @param processLookup finds a running process by PID (for leftovers named in a PID file)
     * @param scheduler     runs the kills of detached processes off the caller's thread
     * @param tmpDir        directory of the server logs and PID files
     * @param shutdownHooks registers the JVM shutdown hook; called once, on the first start
     */
    public ServerRegistry(ProcessLauncher launcher, PortProbe probe, PortStore ports, VersionDetector versionDetector,
            LongFunction<Optional<GodotProcess>> processLookup, FirstResponseWatchdog.Scheduler scheduler,
            Ticker ticker, Path tmpDir, Consumer<Thread> shutdownHooks, Limits limits) {
        this.launcher = launcher;
        this.probe = probe;
        this.ports = ports;
        this.versionDetector = versionDetector;
        this.processLookup = processLookup;
        this.scheduler = scheduler;
        this.ticker = ticker;
        this.tmpDir = tmpDir;
        this.shutdownHooks = shutdownHooks;
        this.limits = limits;
    }

    /** Real processes and sockets, {@code java.io.tmpdir}, kills on a RequestProcessor, production limits. */
    public static ServerRegistry createDefault(PortStore ports) {
        return new ServerRegistry(ProcessLauncher.HEADLESS_GODOT, PortProbe.LOOPBACK, ports, VersionDetector.GODOT,
                pid -> ProcessHandle.of(pid).map(HandleProcess::new),
                new RequestProcessorScheduler(new RequestProcessor(ServerRegistry.class)),
                Ticker.SYSTEM, Path.of(System.getProperty("java.io.tmpdir")),
                Runtime.getRuntime()::addShutdownHook, Limits.DEFAULT);
    }

    /** Key of a project: its absolute path. */
    public static String keyOf(File projectDir) {
        return projectDir.getAbsolutePath();
    }

    /** The server of the project, created on first use. */
    public HeadlessServer forProject(File projectDir) {
        return servers.computeIfAbsent(keyOf(projectDir), k -> new HeadlessServer(this, projectDir));
    }

    /** True once the IDE has begun exiting; the servers are being stopped and must not come back. */
    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    /**
     * IDE_EXIT: refuses any later start, then stops every server (also those being closed) and blocks for at most
     * {@link Limits#stopWaitMs()} in total, however many there are. SETTINGS_CHANGED: kills every server (also
     * those being closed) without blocking, leaves the open/closed state of the projects alone and ends the
     * current session of the killed ones, so a start in progress notices its process is gone. PROJECT_CLOSED has
     * no effect here; use {@link HeadlessServer#close()}.
     */
    public void stopAll(StopReason reason) {
        switch (reason) {
            case IDE_EXIT -> stopForExit();
            case SETTINGS_CHANGED -> stopForSettingsChange();
            case PROJECT_CLOSED -> {
                // per project only
            }
        }
    }

    private void stopForExit() {
        shuttingDown.set(true);
        Map<GodotProcess, HeadlessServer> all = new LinkedHashMap<>();
        servers.values().forEach(s -> s.detachForExit().forEach(p -> all.put(p, s)));
        try {
            terminate(all);
        } finally {
            all.forEach((p, s) -> s.detachedGone(p));
        }
    }

    private void stopForSettingsChange() {
        Map<GodotProcess, HeadlessServer> killed = new LinkedHashMap<>();
        servers.values().forEach(s -> s.detachForSettingsChange().ifPresent(p -> killed.put(p, s)));
        if (!killed.isEmpty()) {
            killInBackground(killed, "Stopping {0} headless Godot LSP server(s): settings changed", killed.size());
        }
    }

    /** Kills detached processes off the caller's thread with one shared deadline, then tells their servers. */
    void killInBackground(Map<GodotProcess, HeadlessServer> owners, String message, Object param) {
        scheduler.schedule(() -> {
            try {
                LOG.log(Level.INFO, message, param);
                terminate(owners);
            } finally {
                owners.forEach((p, s) -> s.detachedGone(p));
            }
        }, 0);
    }

    /** Stops the processes with their descendants: SIGTERM, one shared wait, then a forced kill. Blocks. */
    void terminate(Map<GodotProcess, HeadlessServer> owners) {
        try {
            ProcessTrees.terminateAll(owners.keySet(), TimeUnit.MILLISECONDS.toNanos(limits.stopWaitMs()));
        } finally {
            owners.forEach((p, s) -> s.forget(p));
        }
    }

    /**
     * Safety net for exits that do not go through {@link #stopAll(StopReason) stopAll(IDE_EXIT)}, e.g. SIGTERM.
     * Installed on the first start. Only destroys (the whole process trees); there is no time to wait in a hook.
     */
    void installShutdownHook() {
        if (hookInstalled.compareAndSet(false, true)) {
            shutdownHooks.accept(new Thread(this::destroyAllNow, "godot-lsp-shutdown"));
        }
    }

    private void destroyAllNow() {
        Map<GodotProcess, HeadlessServer> all = new LinkedHashMap<>();
        servers.values().forEach(s -> s.processes().forEach(p -> all.put(p, s)));
        ProcessTrees.destroyAll(all.keySet());
        // PID files stay: a process that survives SIGTERM is then reclaimed by the stale-server policy next session.
    }

    /** Port assigned to the project; a free one is picked and stored on first use. */
    public int portFor(File projectDir) throws IOException {
        synchronized (portLock) {
            return assignPort(projectDir, false);
        }
    }

    /** Picks and stores another free port for the project; its own port is taken by somebody else. */
    int reassignPort(File projectDir) throws IOException {
        synchronized (portLock) {
            return assignPort(projectDir, true);
        }
    }

    /** Must be called under portLock. */
    private int assignPort(File projectDir, boolean forceNew) throws IOException {
        if (!forceNew) {
            int saved = ports.load(projectDir);
            if (saved > 0) {
                return saved;
            }
        }
        Set<Integer> assigned = ports.takenByOthers(projectDir);
        int port = FIRST_PORT;
        while (assigned.contains(port) || !probe.isFree(port)) {
            if (++port >= FIRST_PORT + PORT_RANGE) {
                throw new IOException("No free port for the headless Godot LSP in "
                        + FIRST_PORT + "-" + (FIRST_PORT + PORT_RANGE - 1));
            }
        }
        ports.save(projectDir, port);
        return port;
    }

    /** Refuses an executable whose version is unknown or older than the headless LSP needs. */
    void checkVersion(String exe) throws IOException {
        String version = versions.get(exe);
        if (version == null) {
            // Unknown version: refuse. --version prints nothing parseable only if the binary does not run
            // properly, and a start attempt would then just burn the 30 s timeout.
            Optional<String> detected = versionDetector.detect(exe);
            if (detected.isEmpty()) {
                throw new IOException("Could not detect the Godot version of " + exe
                        + " (the headless LSP needs Godot "
                        + GodotVersion.headlessLspMinVersion() + " or newer)");
            }
            version = detected.get();
            versions.put(exe, version);
        }
        if (!GodotVersion.supportsHeadlessLsp(version)) {
            throw new IOException("Godot " + version + " is too old for the headless LSP (needs "
                    + GodotVersion.headlessLspMinVersion() + " or newer)");
        }
    }
}
