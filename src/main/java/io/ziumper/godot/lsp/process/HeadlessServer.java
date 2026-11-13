package io.ziumper.godot.lsp.process;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;

/**
 * The headless Godot LSP of one project. Starts are serialized by a per-project lock held for the whole start,
 * including the wait for the port; {@link #close()} and the stops never take that lock, so they never block.
 *
 * <p>States (all transitions under {@code lock}, see {@link #moveTo}):
 * <ul>
 * <li>IDLE: no own process, none being killed.
 * <li>STARTING: own process launched, its port has not answered yet (also after a start timed out while Godot
 *     was still importing; the next start waits on the same process).
 * <li>READY: own process alive as of the last start, its port answered.
 * <li>STOPPING: no own process; detached ones are being killed off-thread.
 * </ul>
 * IDLE/STOPPING -> STARTING when a start publishes the process it launched; STARTING -> READY when the port
 * answers; STARTING/READY -> IDLE (or STOPPING) when a start finds the process dead; STARTING/READY -> STOPPING
 * when close(), a settings change or the IDE exit detaches the process; STOPPING -> IDLE when the last detached
 * process is gone.
 */
public final class HeadlessServer {

    private static final Logger LOG = Logger.getLogger(HeadlessServer.class.getName());

    /** Lifecycle of the project's own process; see the class comment. */
    public enum State {
        IDLE, STARTING, READY, STOPPING;

        private Set<State> next() {
            return switch (this) {
                case IDLE -> EnumSet.of(STARTING);
                case STARTING, READY -> EnumSet.of(READY, IDLE, STOPPING);
                case STOPPING -> EnumSet.of(IDLE, STARTING);
            };
        }
    }

    private record Failure(String exe, String message, long time) {
    }

    private final ServerRegistry registry;
    private final File dir;
    /** Project name usable in file names. */
    private final String logName;
    /** Held for a whole start, including the waits; one start per project at a time. */
    private final Object startLock = new Object();
    /** Guards the fields below; never held while waiting or doing I/O. */
    private final Object lock = new Object();
    private State state = State.IDLE;
    /** Own process of the current session, null if none. */
    private GodotProcess process;
    /** Port of {@link #process}, -1 if none. */
    private int port = -1;
    /**
     * Session counter, bumped on every close and settings stop. A start remembers the value it began with and
     * gives up (killing its own process) if it changed, so a start from an ended session never survives, even if
     * the project was reopened meanwhile.
     */
    private long generation;
    /** Last failed start; a new start with the same executable is refused for a while. */
    private Failure failure;
    /** Closed on purpose; nothing is started until {@link #allow()}. */
    private boolean closed;
    /** Processes detached by a close or stop that are still being killed; they may still hold the port. */
    private final List<GodotProcess> detached = new ArrayList<>();
    /** PID file written for each process we started; removed when the process exits or is killed. */
    private final Map<GodotProcess, Path> pidFiles = new ConcurrentHashMap<>();

    HeadlessServer(ServerRegistry registry, File dir) {
        this.registry = registry;
        this.dir = dir;
        this.logName = dir.getName().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Session counter of the project; changes whenever the project is closed or its server stopped for settings. */
    public long generation() {
        synchronized (lock) {
            return generation;
        }
    }

    /** True if no server should run for the project: the IDE is exiting or the project was closed. */
    public boolean isStoppedIntentionally() {
        if (registry.isShuttingDown()) {
            return true;
        }
        synchronized (lock) {
            return closed;
        }
    }

    State state() {
        synchronized (lock) {
            return state;
        }
    }

    /** Port of the own process, -1 if there is none. */
    int port() {
        synchronized (lock) {
            return port;
        }
    }

    /**
     * Makes sure the headless LSP of the project listens on its port. Blocks for up to the ready timeout while
     * the server starts, so it must not be called on the EDT.
     *
     * @param exe Godot executable to run
     * @return the port the server listens on
     * @throws StartRefusedException if the IDE is exiting or the project was closed
     * @throws IOException with a user-presentable reason if the server could not be started
     */
    public int ensureReady(String exe) throws IOException {
        if (SwingUtilities.isEventDispatchThread()) {
            LOG.log(Level.WARNING, "ensureReady called on the EDT; this can block the UI for up to "
                    + registry.limits.readyTimeoutMs() / 1000 + " s", new Throwable());
        }
        // Taken before the first check: a close after this point is always noticed by checkNotStopped.
        long gen = generation();
        checkNotStopped(gen);
        synchronized (startLock) {
            // Checked again: close() may have run while we waited for the lock.
            checkNotStopped(gen);
            Failure last;
            synchronized (lock) {
                last = failure;
            }
            if (last != null && last.exe().equals(exe)
                    && registry.ticker.nanos() - last.time() < nanos(registry.limits.restartBackoffMs())) {
                throw new IOException(last.message());
            }
            try {
                int p = start(exe, gen);
                setFailure(null);
                return p;
            } catch (StartRefusedException ex) {
                throw ex; // not a failure of the server, so no backoff
            } catch (IOException ex) {
                setFailure(new Failure(exe, ex.getMessage(), registry.ticker.nanos()));
                throw ex;
            }
        }
    }

    private void setFailure(Failure f) {
        synchronized (lock) {
            failure = f;
        }
    }

    /**
     * Refuses to start during IDE shutdown, for a project closed on purpose and for a start that began in an
     * earlier session of the project. Only the project's open hook lifts the mark ({@link #allow()}).
     */
    private void checkNotStopped(long gen) throws StartRefusedException {
        if (registry.isShuttingDown()) {
            throw new StartRefusedException("IDE is shutting down");
        }
        synchronized (lock) {
            if (closed || generation != gen) {
                throw new StartRefusedException("project closed");
            }
        }
    }

    /** Called under startLock. */
    private int start(String exe, long gen) throws IOException {
        if (exe == null || !registry.launcher.isExecutable(exe)) {
            throw new IOException("Godot executable not found or not executable: " + exe);
        }
        registry.checkVersion(exe);
        int p = registry.portFor(dir);
        GodotProcess ours = liveProcess();
        if (ours == null) {
            PortProbe probe = registry.probe;
            if (probe.isOpen(p)) {
                // A server of this project from a just closed session may still be dying; the port is free soon.
                // Only if it is still taken after that is it somebody else's.
                waitForDetached(gen);
            }
            if (probe.isOpen(p)) {
                // A headless server of this project left over from an earlier IDE session is ours to kill.
                reclaimStale(p, gen);
            }
            if (probe.isOpen(p)) {
                // Somebody else listens on our port (an editor, a leftover server): never talk to a foreign
                // server, pick another port instead.
                int old = p;
                p = registry.reassignPort(dir);
                LOG.log(Level.INFO, "Port {0} of {1} is taken by another process, using {2}",
                        new Object[]{old, dir, p});
            }
            ours = launch(exe, p);
            registry.installShutdownHook();
            if (!publish(ours, p, gen)) {
                // The IDE exit or close() came after our checks; nobody else has seen this process, so we kill it.
                registry.terminate(Map.of(ours, this));
                throw refusal();
            }
        }
        // Either freshly started, or started by an earlier call that timed out while Godot was still importing.
        waitUntilOpen(p, ours, logFile(p), gen);
        synchronized (lock) {
            if (process == ours && state == State.STARTING) {
                moveTo(State.READY);
            }
        }
        return p;
    }

    private StartRefusedException refusal() {
        return new StartRefusedException(registry.isShuttingDown() ? "IDE is shutting down" : "project closed");
    }

    /** The own process if it is still alive; a dead one is dropped. */
    private GodotProcess liveProcess() {
        synchronized (lock) {
            if (process != null && !process.isAlive()) {
                process = null;
                port = -1;
                moveTo(detached.isEmpty() ? State.IDLE : State.STOPPING);
            }
            return process;
        }
    }

    /** Makes a freshly launched process the own one, unless the session ended or the IDE exits meanwhile. */
    private boolean publish(GodotProcess p, int onPort, long gen) {
        synchronized (lock) {
            if (registry.isShuttingDown() || closed || generation != gen) {
                return false;
            }
            process = p;
            port = onPort;
            moveTo(State.STARTING);
            return true;
        }
    }

    /** Must be called under lock. */
    private void moveTo(State to) {
        assert state == to || state.next().contains(to) : "illegal transition " + state + " -> " + to;
        LOG.log(Level.FINEST, "{0}: {1} -> {2}", new Object[]{dir, state, to});
        state = to;
    }

    /**
     * If a process of this project is still being killed after a close, waits until it is gone (at most the stop
     * limit + 1 s; the kill itself is bounded by the stop limit). Gives up at once, like waitUntilOpen, if the
     * project is closed again or the IDE exits.
     */
    private void waitForDetached(long gen) throws IOException {
        if (!hasDetached()) {
            return;
        }
        LOG.log(Level.FINE, "Port of {0} is busy, waiting for its closing server to exit", dir);
        long end = registry.ticker.nanos() + nanos(registry.limits.stopWaitMs() + 1000);
        while (hasDetached() && registry.ticker.nanos() < end) {
            checkNotStopped(gen);
            pause(registry.limits.exitPollMs(), "Interrupted while waiting for the closing Godot LSP");
        }
    }

    private boolean hasDetached() {
        synchronized (lock) {
            return !detached.isEmpty();
        }
    }

    /**
     * Kills a leftover headless Godot of this project on the port, if its PID file proves it is one, and waits
     * (at most the stop limit) for the port to be released. Does nothing otherwise.
     */
    private void reclaimStale(int onPort, long gen) throws IOException {
        Path pidPath = pidPath(onPort);
        StaleServerPolicy.Decision decision = StaleServerPolicy.decide(PidFile.read(pidPath), dir, onPort,
                registry.processLookup);
        if (!decision.kill()) {
            LOG.log(Level.FINE, "Port {0} of {1} is not a stale server: {2}",
                    new Object[]{onPort, dir, decision.reason()});
            return;
        }
        GodotProcess stale = decision.process();
        ProcessTrees.terminateAll(List.of(stale), nanos(registry.limits.stopWaitMs()));
        deletePidFile(pidPath, stale.pid());
        LOG.log(Level.INFO, "Killed stale headless Godot LSP (pid {0}) of {1} on port {2}",
                new Object[]{stale.pid(), dir, onPort});
        long end = registry.ticker.nanos() + nanos(registry.limits.stopWaitMs());
        while (registry.probe.isOpen(onPort) && registry.ticker.nanos() < end) {
            checkNotStopped(gen);
            pause(registry.limits.exitPollMs(), "Interrupted while waiting for the stale Godot LSP to exit");
        }
    }

    private GodotProcess launch(String exe, int onPort) throws IOException {
        File log = logFile(onPort);
        GodotProcess p;
        try {
            p = registry.launcher.launch(exe, dir, onPort, log);
        } catch (IOException ex) {
            throw new IOException("Could not start " + exe + ": " + ex.getMessage(), ex);
        }
        LOG.log(Level.INFO, "Started headless Godot LSP for {0} on port {1}, log: {2}",
                new Object[]{dir, onPort, log});
        Path pidPath = pidPath(onPort);
        try {
            new PidFile(p.pid(), onPort, dir.getAbsolutePath(), System.currentTimeMillis()).write(pidPath);
            pidFiles.put(p, pidPath);
            p.onExit().thenRun(() -> forget(p));
        } catch (IOException ex) {
            LOG.log(Level.FINE, "Could not write " + pidPath, ex);
        }
        return p;
    }

    /** Waits until the port accepts connections; fails at once, with the exit code, if our process exits first. */
    private void waitUntilOpen(int onPort, GodotProcess p, File log, long gen) throws IOException {
        long timeoutMs = registry.limits.readyTimeoutMs();
        long end = registry.ticker.nanos() + nanos(timeoutMs);
        while (registry.ticker.nanos() < end) {
            // close() marks the project without waiting for our lock; give up so we do not wait out the timeout.
            // The process was detached by close() (or the stop), which kills it, so there is nothing to clean up.
            checkNotStopped(gen);
            if (!p.isAlive()) {
                throw new IOException("Godot exited"
                        + p.exitCode().stream().mapToObj(c -> " with code " + c).findFirst().orElse("")
                        + " before the LSP came up, see " + log);
            }
            if (registry.probe.isOpen(onPort)) {
                return;
            }
            pause(registry.limits.readyPollMs(), "Interrupted while waiting for the Godot LSP");
        }
        throw new IOException("Godot LSP did not open port " + onPort + " within " + timeoutMs / 1000
                + " s, see " + log);
    }

    private void pause(long ms, String interrupted) throws IOException {
        try {
            registry.ticker.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted, ex);
        }
    }

    /**
     * Called from the project's close hook, possibly on the EDT: never blocks and takes no start lock. Marks the
     * project closed, ends the current session and detaches the process of that session; the detached process
     * (this very object, so never a server started after a reopen) is killed off-thread. A start in progress
     * notices the mark and gives up.
     */
    public void close() {
        GodotProcess p;
        synchronized (lock) {
            closed = true;
            generation++;
            failure = null;
            p = detachLocked();
        }
        if (p != null) {
            registry.killInBackground(Map.of(p, this), "Stopping headless Godot LSP of {0}", dir);
        }
    }

    /**
     * Called from the project's open hook: lifts the mark set by {@link #close()}. Does nothing for a project
     * that was not marked (e.g. projects opened at IDE startup). Cheap, never blocks.
     */
    public void allow() {
        synchronized (lock) {
            closed = false;
            failure = null;
        }
    }

    /**
     * PROJECT_CLOSED: as {@link #close()}. SETTINGS_CHANGED: kills the process off-thread and ends the session,
     * leaving the project open. IDE_EXIT: kills the processes of this project and waits for them (at most the stop
     * limit); refusing later starts is the registry's job ({@link ServerRegistry#stopAll}).
     */
    public void stop(StopReason reason) {
        switch (reason) {
            case PROJECT_CLOSED -> close();
            case SETTINGS_CHANGED -> detachForSettingsChange().ifPresent(p -> registry.killInBackground(Map.of(p, this),
                    "Stopping {0} headless Godot LSP server(s): settings changed", 1));
            case IDE_EXIT -> {
                Map<GodotProcess, HeadlessServer> all = new LinkedHashMap<>();
                detachForExit().forEach(p -> all.put(p, this));
                try {
                    registry.terminate(all);
                } finally {
                    all.keySet().forEach(this::detachedGone);
                }
            }
        }
    }

    /** Detaches the own process for a settings change and ends the session if there was one. */
    Optional<GodotProcess> detachForSettingsChange() {
        synchronized (lock) {
            GodotProcess p = detachLocked();
            if (p != null) {
                generation++;
            }
            return Optional.ofNullable(p);
        }
    }

    /** Detaches the own process for the IDE exit; returns it with those already being killed. */
    List<GodotProcess> detachForExit() {
        synchronized (lock) {
            detachLocked();
            return new ArrayList<>(detached);
        }
    }

    /** Must be called under lock. */
    private GodotProcess detachLocked() {
        GodotProcess p = process;
        if (p != null) {
            process = null;
            port = -1;
            detached.add(p);
            moveTo(State.STOPPING);
        }
        return p;
    }

    /** A detached process was killed (or given up on); a waiting start may go on. */
    void detachedGone(GodotProcess p) {
        synchronized (lock) {
            detached.remove(p);
            if (detached.isEmpty() && state == State.STOPPING) {
                moveTo(State.IDLE);
            }
        }
    }

    /** The own process and the detached ones. */
    List<GodotProcess> processes() {
        synchronized (lock) {
            List<GodotProcess> all = new ArrayList<>(detached);
            if (process != null) {
                all.add(0, process);
            }
            return all;
        }
    }

    /** Removes the PID file of a process that exited or was killed. */
    void forget(GodotProcess p) {
        Path path = pidFiles.remove(p);
        if (path != null) {
            deletePidFile(path, p.pid());
        }
    }

    /** Removes the PID file if it still belongs to the given process; failures are only logged. */
    private static void deletePidFile(Path path, long pid) {
        try {
            Optional<PidFile> f = PidFile.read(path);
            if (f.isPresent() && f.get().pid() == pid) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException ex) {
            LOG.log(Level.FINE, "Could not delete " + path, ex);
        }
    }

    /** Log of the server on the port; overwritten on every start. */
    File logFile(int onPort) {
        return new File(registry.tmpDir.toFile(), "godot-lsp-" + logName + "-" + onPort + ".log");
    }

    Path pidPath(int onPort) {
        return PidFile.pathFor(registry.tmpDir, logName, onPort);
    }

    private static long nanos(long ms) {
        return TimeUnit.MILLISECONDS.toNanos(ms);
    }
}
