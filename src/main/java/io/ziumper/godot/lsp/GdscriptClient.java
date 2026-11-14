package io.ziumper.godot.lsp;

import io.ziumper.godot.GodotIcons;
import io.ziumper.godot.lsp.process.RequestProcessorScheduler;
import io.ziumper.godot.lsp.process.StartRefusedException;
import io.ziumper.godot.options.GodotSettings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import javax.swing.Icon;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.api.editor.mimelookup.MimeRegistration;
import org.netbeans.modules.lsp.client.spi.LanguageIdResolver;
import org.netbeans.modules.lsp.client.spi.LanguageServerProvider;
import org.netbeans.modules.lsp.client.spi.ServerRestarter;
import org.netbeans.api.project.Project;
import org.openide.awt.NotificationDisplayer;
import org.openide.filesystems.FileUtil;
import org.openide.util.ImageUtilities;
import org.openide.util.Lookup;
import org.openide.util.NbBundle.Messages;
import org.openide.util.RequestProcessor;
import org.openide.util.lookup.Lookups;

/**
 * Connects GDScript editors directly to the Godot Editor LSP server over TCP.
 */
@Messages({
    "MSG_LspUnreachableTitle=Godot LSP not connected",
    "MSG_LspUnreachableText=Could not reach the Godot LSP at {0}. Open the project in the Godot Editor, then reopen the .gd file.",
    "MSG_LspConnectedTitle=Godot LSP connected",
    "MSG_LspConnectedText=Connected to the Godot LSP at {0}.",
    "MSG_LspHeadlessFailedTitle=Godot headless LSP not started",
    "# {0} - reason",
    "MSG_LspHeadlessFailedText=Could not start the Godot LSP in the background: {0}",
    "# {0} - host:port",
    "MSG_LspHeadlessUnreachableText=Could not connect to the headless Godot LSP at {0}.",
    "MSG_LspLostTitle=Godot LSP disconnected",
    "MSG_LspLostText=The connection to the Godot LSP at {0} was lost. Completion and diagnostics stop until it reconnects.",
    "MSG_LspNoResponseTitle=Godot LSP not responding",
    "# {0} - host:port",
    "# {1} - seconds",
    "MSG_LspNoResponseText=The Godot LSP at {0} accepted the connection but did not answer within {1} s."
})
@MimeRegistration(mimeType = GdscriptClient.MIME_TYPE, service = LanguageServerProvider.class)
public class GdscriptClient implements LanguageServerProvider {

    static final String MIME_TYPE = "text/gdscript";
    private static final Logger LOG = Logger.getLogger(GdscriptClient.class.getName());
    private static final RequestProcessor RP = new RequestProcessor(GdscriptClient.class);
    /**
     * Starts headless servers and probes the Godot editor's LSP for startServer. A start waits for Godot for up to
     * the ready timeout, so it does not share RP with the first-response watchdogs and the retry loop; projects
     * start in parallel.
     */
    private static final RequestProcessor STARTER = new RequestProcessor("Godot LSP start", 4);
    /** Keys (see {@link #clientKey}) of the servers STARTER is starting or probing, one task each. Guarded by LOCK. */
    private static final Set<String> PENDING = new HashSet<>();
    /**
     * Editor-mode targets (host:port) known to accept connections: a probe or a connection succeeded and none has
     * failed or been lost since. Only these are connected to in startServer; any other is probed first, off the
     * caller's thread. Guarded by LOCK.
     */
    private static final Set<String> REACHABLE = new HashSet<>();

    /** The single shared retry task; one loop serves every server that waits for a retry. Guarded by LOCK. */
    private static RequestProcessor.Task retryTask;
    /**
     * Retry state per server, keyed by project (see {@link #clientKey}). Created by startServer, removed when the
     * project is closed on purpose, when the loop gives up on it, or when its connection is closed by the client
     * of a closed project; so it only holds projects that are open or still being retried. Guarded by LOCK.
     */
    private static final Map<String, RetryState> STATES = new HashMap<>();
    /** The retry loop gives up on a server after this long without reaching it; the next startServer re-arms it. */
    private static final long RETRY_GIVE_UP_NANOS = TimeUnit.MINUTES.toNanos(10);
    /** Key of the files that belong to no project. */
    private static final String NO_PROJECT_KEY = "<no project>";
    /** languageId Godot's LSP server recognises. */
    static final String LANGUAGE_ID = "gdscript";
    private static final Object LOCK = new Object();
    /**
     * Restarters of headless connections that were refused or cut because their project is closed, per project
     * (key as in GodotLspServer). Restarted, and only these, when the project is reopened. Guarded by LOCK.
     */
    private static final Map<String, ServerRestarter> PENDING_RESTART = new HashMap<>();
    /** Notifications require a non-null icon. */
    private static final Icon ICON = ImageUtilities.loadImageIcon(GodotIcons.GODOT_ICON, false);

    /**
     * Last failure message logged, so repeated identical failures do not flood messages.log. Global on purpose:
     * notifications are not per project, and in the editor mode every project fails with the same message.
     * Guarded by LOCK.
     */
    private static String lastFailure;

    /** What the retry loop needs to know about one server. All fields guarded by LOCK. */
    private static final class RetryState {
        ServerRestarter restarter;
        /** Project directory, null if unknown (no project or not a local directory). */
        File dir;
        /** The connection believed alive, null when there is none. */
        LspConnection connection;
        /** True until a connection is announced; set again after a failure or a lost connection, per server. */
        boolean announce = true;
        /** True while the retry loop should restart this server. */
        boolean waiting;
        /** Start of the current run of failures (nanoTime), 0 when none; the give-up time is counted from it. */
        long failStart;
    }

    /** Key of the server a startServer call is for: the project directory, as in GodotLspServer. */
    private static String clientKey(Project prj, File dir) {
        if (prj == null) {
            return NO_PROJECT_KEY;
        }
        return dir != null ? GodotLspServer.keyOf(dir) : prj.getProjectDirectory().getPath();
    }

    /**
     * The state of the server; created when a restarter is known. Without one nothing could be retried, so a
     * throwaway object is returned and no entry is leaked. Must be called under LOCK.
     */
    private static RetryState stateFor(String key, ServerRestarter restarter, File dir) {
        RetryState s = STATES.get(key);
        if (s == null) {
            s = new RetryState();
            if (restarter != null) {
                STATES.put(key, s);
            }
        }
        if (restarter != null) {
            s.restarter = restarter;
        }
        if (dir != null) {
            s.dir = dir;
        }
        return s;
    }

    @Override
    public LanguageServerDescription startServer(Lookup lkp) {
        ServerRestarter restarter = lkp.lookup(ServerRestarter.class);
        Project prj = lkp.lookup(Project.class);
        File projectDir = prj != null ? FileUtil.toFile(prj.getProjectDirectory()) : null;
        String key = clientKey(prj, projectDir);
        synchronized (LOCK) {
            stateFor(key, restarter, projectDir);
        }
        String host = GodotSettings.getHost();
        int port = GodotSettings.getPort();

        boolean headless = GodotSettings.isHeadlessLsp() && prj != null;
        long generation = 0;
        if (headless) {
            // Own server on loopback; host/port from the settings are for the editor mode only.
            host = GodotSettings.DEFAULT_HOST;
            if (projectDir == null) {
                String reason = "The project is not in a local directory";
                reportFailure(key, reason, Bundle.MSG_LspHeadlessFailedTitle(),
                        Bundle.MSG_LspHeadlessFailedText(reason), null);
                scheduleRetry(key, restarter, null);
                return null;
            }
            generation = GodotLspServer.generation(projectDir);
            if (GodotLspServer.isStoppedIntentionally(projectDir)) {
                // IDE exit or closed project: not a failure, and nothing to retry.
                LOG.log(Level.FINE, "Headless Godot LSP of {0} not started: project closed or IDE exiting",
                        projectDir);
                rememberForReopen(projectDir, restarter);
                return null;
            }
            port = GodotLspServer.readyPort(projectDir);
            if (port < 0) {
                // lsp.client calls this on the EDT (a .gd file opened, edited or closed) and always under its
                // global lock, and starting Godot takes seconds: start it in the background, connect afterwards.
                startInBackground(key, prj, restarter, projectDir);
                return null;
            }
        } else if (!isReachable(host + ":" + port)) {
            // Same threads and lock: a connect to an editor that is not running blocks until the connect timeout,
            // and even a refused loopback connect takes about 2 s on Windows. Probe first, in the background.
            probeInBackground(key, prj, restarter, projectDir, host, port);
            return null;
        }
        LOG.log(Level.INFO, "startServer called for Godot LSP: project={0}, target={1}:{2}",
                new Object[]{prj != null ? prj.getProjectDirectory().getPath() : "<none>", host, port});

        if (headless) {
            synchronized (LOCK) {
                PENDING_RESTART.remove(key); // served again, nothing left to restart
            }
        }
        String target = host + ":" + port;
        LspConnection connection;
        try {
            connection = LspConnection.connect(host, port, GodotSettings.getConnectTimeoutMs(),
                    new RequestProcessorScheduler(RP),
                    new Session(key, target, restarter, projectDir, headless, generation));
        } catch (IOException ex) {
            if (headless && GodotLspServer.isStoppedIntentionally(projectDir)) {
                LOG.log(Level.FINE, "Headless Godot LSP at {0} is gone on purpose: {1}", new Object[]{target, ex});
                rememberForReopen(projectDir, restarter);
                return null;
            }
            if (headless) {
                reportFailure(key, target + " " + ex, Bundle.MSG_LspHeadlessFailedTitle(),
                        Bundle.MSG_LspHeadlessUnreachableText(target), ex);
            } else {
                setReachable(target, false);
                reportFailure(key, target + " " + ex, Bundle.MSG_LspUnreachableTitle(),
                        Bundle.MSG_LspUnreachableText(target), ex);
            }
            scheduleRetry(key, restarter, projectDir);
            return null;
        }
        synchronized (LOCK) {
            RetryState state = stateFor(key, restarter, projectDir);
            state.connection = connection;
            state.waiting = false;
            // lastFailure, failStart and announce are settled by Session.firstResponse, not by the bare TCP connect.
        }
        LOG.log(Level.INFO, "Connected to Godot LSP at {0}:{1}", new Object[]{host, port});
        connection.watchFirstResponse(GodotSettings.FIRST_RESPONSE_TIMEOUT_MS);
        return createDescription(new GodotNotificationFilter(connection.input(), DiagnosticRanges::clampToOpenDocument),
                connection.output(), connection);
    }

    /**
     * Starts the project's headless server on {@link #STARTER}, one start per project at a time, and then asks
     * lsp.client to connect; that calls startServer again, which now finds the server ready. A failed start is
     * reported and retried like a failed connect; a refused one (project closed, IDE exiting) waits for the reopen.
     */
    private static void startInBackground(String key, Project prj, ServerRestarter restarter, File projectDir) {
        if (!beginBackground(key)) {
            return; // the start in progress connects the editors when it is done
        }
        LOG.log(Level.FINE, "Starting the headless Godot LSP of {0} in the background", projectDir);
        STARTER.post(() -> {
            boolean ready = false;
            try {
                GodotLspServer.ensureRunning(GodotSettings.getGodotExecutable(), projectDir);
                ready = true;
            } catch (StartRefusedException ex) {
                LOG.log(Level.FINE, "Headless Godot LSP not started: {0}", ex.getMessage());
                rememberForReopen(projectDir, restarter);
            } catch (IOException ex) {
                reportFailure(key, ex.getMessage(), Bundle.MSG_LspHeadlessFailedTitle(),
                        Bundle.MSG_LspHeadlessFailedText(ex.getMessage()), ex);
                scheduleRetry(key, restarter, projectDir);
            } finally {
                endBackground(key);
            }
            if (ready) {
                connectNow(prj);
            }
        });
    }

    /**
     * Probes the Godot editor's LSP on {@link #STARTER}, one probe per server at a time. If it listens, asks
     * lsp.client to connect; startServer then finds the target reachable. Otherwise the failure is reported and
     * retried like a failed connect.
     */
    private static void probeInBackground(String key, Project prj, ServerRestarter restarter, File dir,
            String host, int port) {
        if (!beginBackground(key)) {
            return; // the probe in progress connects the editors when it is done
        }
        String target = host + ":" + port;
        LOG.log(Level.FINE, "Probing the Godot LSP at {0} in the background", target);
        STARTER.post(() -> {
            IOException failure;
            try {
                failure = probe(host, port);
            } finally {
                endBackground(key);
            }
            if (failure == null) {
                connectNow(prj);
            } else {
                reportFailure(key, target + " " + failure, Bundle.MSG_LspUnreachableTitle(),
                        Bundle.MSG_LspUnreachableText(target), failure);
                scheduleRetry(key, restarter, dir);
            }
        });
    }

    /** Claims the single background start or probe of the server; false if one is already running. */
    private static boolean beginBackground(String key) {
        synchronized (LOCK) {
            return PENDING.add(key);
        }
    }

    private static void endBackground(String key) {
        synchronized (LOCK) {
            PENDING.remove(key);
        }
    }

    /**
     * Asks lsp.client to connect the project's server now; it calls startServer on this thread. Files outside a
     * project cannot be named to lsp.client this way: they connect on their next request.
     */
    private static void connectNow(Project prj) {
        if (prj != null) {
            LspClientBridge.ensureServerRunning(prj, MIME_TYPE);
        }
    }

    private static boolean isReachable(String target) {
        synchronized (LOCK) {
            return REACHABLE.contains(target);
        }
    }

    private static void setReachable(String target, boolean reachable) {
        synchronized (LOCK) {
            if (reachable) {
                REACHABLE.add(target);
            } else {
                REACHABLE.remove(target);
            }
        }
    }

    /**
     * Records a failed start. INFO and a notification once per distinct failure; details at FINE afterwards.
     * A later successful connection resets it.
     */
    private static void reportFailure(String key, String failure, String title, String text, Exception ex) {
        boolean first;
        synchronized (LOCK) {
            first = !failure.equals(lastFailure);
            lastFailure = failure;
            RetryState state = STATES.get(key);
            if (state != null) {
                state.announce = true;
                state.connection = null;
            }
        }
        LOG.log(first ? Level.INFO : Level.FINE, "Godot LSP not available: " + failure, ex);
        if (first) {
            NotificationDisplayer.getDefault().notify(title, ICON, text, null, NotificationDisplayer.Priority.NORMAL);
        }
    }

    /**
     * What a connection was made for, and the single place that reacts to its end. A close by this client
     * (restart, server stop) is neither reported nor retried. A headless server stopped on purpose (project
     * closed, IDE exiting, settings changed) is neither reported nor retried either; its connection is restarted
     * when the project is reopened. Any other end, including a server that never answered, is reported and a
     * reconnect is scheduled.
     *
     * @param key        server key, see {@link #clientKey}
     * @param projectDir project directory, null if unknown
     * @param headless   true if the server is a headless one of the project
     * @param generation session of the project when the connection was made (headless only); a different current
     *                   one means the project was closed (and maybe reopened) since, so the end is on purpose
     */
    private record Session(String key, String target, ServerRestarter restarter, File projectDir, boolean headless,
            long generation) implements LspConnection.Listener {

        @Override
        public void firstResponse(LspConnection connection) {
            boolean announce;
            synchronized (LOCK) {
                lastFailure = null;
                RetryState state = stateFor(key, restarter, projectDir);
                state.failStart = 0;
                announce = state.announce;
                state.announce = false;
            }
            if (announce) {
                NotificationDisplayer.getDefault().notify(
                        Bundle.MSG_LspConnectedTitle(),
                        ICON,
                        Bundle.MSG_LspConnectedText(target),
                        null,
                        NotificationDisplayer.Priority.NORMAL);
            }
        }

        @Override
        public void ended(LspConnection connection, LspConnection.CloseCause cause) {
            if (cause == LspConnection.CloseCause.REQUESTED) {
                detached(key, connection, projectDir);
                return;
            }
            connectionEnded(key, connection);
            if (!headless) {
                setReachable(target, false); // e.g. the editor was closed; the next start probes again first
            }
            if (headless && (GodotLspServer.isStoppedIntentionally(projectDir)
                    || GodotLspServer.generation(projectDir) != generation)) {
                LOG.log(Level.FINE, "Headless Godot LSP at {0} was stopped on purpose", target);
                rememberForReopen(projectDir, restarter);
                return;
            }
            if (cause == LspConnection.CloseCause.NO_RESPONSE) {
                long seconds = GodotSettings.FIRST_RESPONSE_TIMEOUT_MS / 1000;
                reportFailure(key, target + " did not answer within " + seconds + " s",
                        Bundle.MSG_LspNoResponseTitle(), Bundle.MSG_LspNoResponseText(target, seconds), null);
            } else {
                LOG.log(Level.INFO, "Lost connection to Godot LSP at {0}", target);
                NotificationDisplayer.getDefault().notify(
                        Bundle.MSG_LspLostTitle(),
                        ICON,
                        Bundle.MSG_LspLostText(target),
                        null,
                        NotificationDisplayer.Priority.NORMAL);
            }
            scheduleRetry(key, restarter, projectDir);
        }
    }

    /** The connection of the server broke by itself: the next connect is announced again. */
    private static void connectionEnded(String key, LspConnection connection) {
        synchronized (LOCK) {
            RetryState state = STATES.get(key);
            if (state != null && state.connection == connection) {
                state.connection = null;
                state.announce = true;
            }
        }
    }

    /**
     * The client closed the connection (restart, project closed, IDE exit). Nothing to report or retry. The state
     * is dropped if the project is closed for good, otherwise it stays: a restart follows and reuses it, which
     * keeps the connect announcement from repeating on every client-side restart.
     */
    private static void detached(String key, LspConnection connection, File projectDir) {
        synchronized (LOCK) {
            RetryState state = STATES.get(key);
            if (state != null && state.connection == connection) {
                state.connection = null;
                if (projectDir != null && GodotLspServer.isStoppedIntentionally(projectDir)) {
                    STATES.remove(key);
                }
            }
        }
    }

    /**
     * Godot ignores didOpen with a MIME-type languageId ("text/gdscript", the lsp.client default) and then
     * answers every completion with an empty list; it needs the plain id "gdscript". A LanguageIdResolver
     * passed in the description's Lookup makes lsp.client send that id.
     */
    private static LanguageServerDescription createDescription(InputStream in, OutputStream out, Process process) {
        LanguageIdResolver resolver = file -> LANGUAGE_ID;
        return LanguageServerDescription.create(in, out, process, Lookups.singleton(resolver));
    }

    /**
     * Remembers the restarter of a headless connection of a closed project, to restart it when the project is
     * reopened (its .gd files stay open in the editor, so nothing else would reconnect them). If the project is
     * already open again by now (the reopen came first), restarts at once.
     */
    private static void rememberForReopen(File projectDir, ServerRestarter restarter) {
        if (projectDir == null || restarter == null) {
            return;
        }
        synchronized (LOCK) {
            String key = GodotLspServer.keyOf(projectDir);
            STATES.remove(key); // the pending entry takes over; the next startServer creates the state again
            PENDING_RESTART.put(key, restarter);
        }
        if (!GodotLspServer.isStoppedIntentionally(projectDir)) {
            projectAllowed(projectDir);
        }
    }

    /**
     * Called from the project's open hook after the project was allowed again: restarts, off the calling
     * thread, only the connections of that project that were refused or cut while it was closed. Does not
     * touch other projects or a working connection.
     */
    public static void projectAllowed(File projectDir) {
        ServerRestarter restarter;
        synchronized (LOCK) {
            restarter = PENDING_RESTART.remove(GodotLspServer.keyOf(projectDir));
        }
        if (restarter != null && GodotSettings.isHeadlessLsp()) {
            RP.post(restarter::restart);
        }
    }

    /**
     * Restarts the LSP client of every known server, e.g. after the connection settings changed. Servers waiting
     * for a retry are restarted here too and leave the retry loop, so none is restarted twice; if the restart
     * fails, startServer arms the retry again. Does nothing if no server is known (no .gd editor has started one).
     */
    public static void reconnect() {
        List<ServerRestarter> restarters = new ArrayList<>();
        synchronized (LOCK) {
            for (RetryState state : STATES.values()) {
                state.waiting = false;
                state.failStart = 0;
                restarters.add(state.restarter);
            }
        }
        if (!restarters.isEmpty()) {
            RP.post(() -> restarters.forEach(ServerRestarter::restart));
        }
    }

    /** Marks the server for the retry loop and starts the loop if it is not running. */
    private static void scheduleRetry(String key, ServerRestarter restarter, File dir) {
        if (!GodotSettings.isAutoReconnect()) {
            return;
        }
        synchronized (LOCK) {
            RetryState state = stateFor(key, restarter, dir);
            if (state.restarter == null) {
                return;
            }
            state.waiting = true;
            if (retryTask == null) {
                retryTask = RP.create(GdscriptClient::retry);
            }
            if (retryTask.isFinished()) {
                retryTask.schedule(GodotSettings.getRetryIntervalMs());
            }
        }
    }

    /**
     * One pass of the shared retry loop over every waiting server. A server leaves the loop when it is
     * restarted (startServer, or the background start it began, arms it again if that fails, so a slow headless
     * start is never overlapped by the next pass), when its project was closed on purpose, or when it has been
     * failing for too long. The loop reschedules itself while some server still waits for its host to come up,
     * and ends otherwise.
     */
    private static void retry() {
        boolean headless = GodotSettings.isHeadlessLsp();
        List<RetryState> work = new ArrayList<>();
        Map<File, ServerRestarter> closed = new HashMap<>();
        synchronized (LOCK) {
            boolean auto = GodotSettings.isAutoReconnect();
            long now = System.nanoTime();
            for (Iterator<Map.Entry<String, RetryState>> it = STATES.entrySet().iterator(); it.hasNext();) {
                RetryState state = it.next().getValue();
                if (!state.waiting) {
                    continue;
                }
                if (!auto || state.connection != null) {
                    state.waiting = false; // retry switched off, or somebody (e.g. lsp.client itself) reconnected
                    continue;
                }
                if (state.dir != null && GodotLspServer.isStoppedIntentionally(state.dir)) {
                    // Project closed (or IDE exiting): nothing to retry, and no entry is left behind.
                    it.remove();
                    if (headless && !GodotLspServer.isShuttingDown()) {
                        closed.put(state.dir, state.restarter);
                    }
                    continue;
                }
                if (state.failStart == 0) {
                    state.failStart = now;
                } else if (now - state.failStart > RETRY_GIVE_UP_NANOS) {
                    it.remove();
                    LOG.log(Level.INFO, "Giving up reconnecting to the Godot LSP of {0}; it will be retried when a .gd file is opened",
                            state.dir != null ? state.dir : "<no project>");
                    continue;
                }
                work.add(state);
            }
        }
        closed.forEach(GdscriptClient::rememberForReopen);
        Boolean reachable = null;
        for (RetryState state : work) {
            if (!headless) {
                if (reachable == null) {
                    String host = GodotSettings.getHost();
                    int port = GodotSettings.getPort();
                    reachable = probe(host, port) == null;
                    if (reachable) {
                        LOG.log(Level.INFO, "Godot LSP reachable at {0}:{1}, restarting clients",
                                new Object[]{host, port});
                    }
                }
                if (!reachable) {
                    continue; // stays waiting
                }
            }
            ServerRestarter restarter;
            synchronized (LOCK) {
                state.waiting = false;
                if (!headless) {
                    state.failStart = 0;
                }
                restarter = state.restarter;
            }
            restarter.restart();
        }
        synchronized (LOCK) {
            for (RetryState state : STATES.values()) {
                if (state.waiting) {
                    retryTask.schedule(GodotSettings.getRetryIntervalMs());
                    break;
                }
            }
        }
    }

    /**
     * Whether the Godot editor LSP at host:port accepts connections: null if it does, else why not. Blocks for up
     * to the connect timeout, so never on the EDT. The answer is recorded for startServer (see REACHABLE).
     */
    private static IOException probe(String host, int port) {
        String target = host + ":" + port;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), GodotSettings.getConnectTimeoutMs());
        } catch (IOException ex) {
            setReachable(target, false);
            return ex;
        }
        setReachable(target, true);
        return null;
    }
}
