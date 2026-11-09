package io.ziumper.godot.lsp;

import io.ziumper.godot.GodotIcons;
import io.ziumper.godot.options.GodotSettings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
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
    "MSG_LspLostText=The connection to the Godot LSP at {0} was lost. Completion and diagnostics stop until it reconnects."
})
@MimeRegistration(mimeType = "text/gdscript", service = LanguageServerProvider.class)
public class GdscriptClient implements LanguageServerProvider {

    private static final Logger LOG = Logger.getLogger(GdscriptClient.class.getName());
    private static final RequestProcessor RP = new RequestProcessor(GdscriptClient.class);

    /** The single pending retry task; guarantees one retry loop at a time. Guarded by LOCK. */
    private static RequestProcessor.Task retryTask;
    /** Restarter of the most recent startServer call; the retry loop always restarts this one. Guarded by LOCK. */
    private static ServerRestarter currentRestarter;
    /** True while a connection made by startServer is believed alive. Guarded by LOCK. */
    private static boolean connected;
    /** Start of the current run of failed retries (nanoTime), 0 when none. Guarded by LOCK. */
    private static long retryStart;
    /** The retry loop gives up after this long without reaching the server; the next startServer re-arms it. */
    private static final long RETRY_GIVE_UP_NANOS = TimeUnit.MINUTES.toNanos(10);
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

    /** Last failure message logged, so repeated identical failures do not flood messages.log. */
    private static String lastFailure;
    /** True until a connection is announced; set again after a failure or a lost connection. Guarded by LOCK. */
    private static boolean announceConnect = true;

    @Override
    public LanguageServerDescription startServer(Lookup lkp) {
        ServerRestarter restarter = lkp.lookup(ServerRestarter.class);
        if (restarter != null) {
            synchronized (LOCK) {
                currentRestarter = restarter;
            }
        }
        String host = GodotSettings.getHost();
        int port = GodotSettings.getPort();

        Project prj = lkp.lookup(Project.class);
        boolean headless = GodotSettings.isHeadlessLsp() && prj != null;
        long generation = 0;
        if (headless) {
            // Own server on loopback; host/port from the settings are for the editor mode only.
            host = GodotSettings.DEFAULT_HOST;
            File dir = FileUtil.toFile(prj.getProjectDirectory());
            try {
                if (dir == null) {
                    throw new IOException("The project is not in a local directory");
                }
                generation = GodotLspServer.generation(dir);
                port = GodotLspServer.ensureRunning(GodotSettings.getGodotExecutable(), dir);
            } catch (GodotLspServer.StartRefusedException ex) {
                // IDE exit or closed project: not a failure, and nothing to retry.
                LOG.log(Level.FINE, "Headless Godot LSP not started: {0}", ex.getMessage());
                rememberForReopen(dir, restarter);
                return null;
            } catch (IOException ex) {
                reportFailure(ex.getMessage(), Bundle.MSG_LspHeadlessFailedTitle(),
                        Bundle.MSG_LspHeadlessFailedText(ex.getMessage()), ex);
                scheduleRetry(restarter);
                return null;
            }
        }
        LOG.log(Level.INFO, "startServer called for Godot LSP: project={0}, target={1}:{2}",
                new Object[]{prj != null ? prj.getProjectDirectory().getPath() : "<none>", host, port});

        File headlessDir = headless ? FileUtil.toFile(prj.getProjectDirectory()) : null;
        if (headlessDir != null) {
            synchronized (LOCK) {
                PENDING_RESTART.remove(GodotLspServer.keyOf(headlessDir)); // served again, nothing left to restart
            }
        }
        final long startedGeneration = generation;
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.connect(new InetSocketAddress(host, port), GodotSettings.getConnectTimeoutMs());
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            boolean announce;
            synchronized (LOCK) {
                lastFailure = null;
                connected = true;
                retryStart = 0;
                announce = announceConnect;
                announceConnect = false;
            }
            LOG.log(Level.INFO, "Connected to Godot LSP at {0}:{1}", new Object[]{host, port});
            String target = host + ":" + port;
            if (announce) {
                NotificationDisplayer.getDefault().notify(
                        Bundle.MSG_LspConnectedTitle(),
                        ICON,
                        Bundle.MSG_LspConnectedText(target),
                        null,
                        NotificationDisplayer.Priority.NORMAL);
            }
            return createDescription(new GodotNotificationFilter(watchForLoss(socket, in, target, restarter, headlessDir, startedGeneration)), out,
                    new SocketProcess(socket));
        } catch (IOException ex) {
            closeQuietly(socket);
            String target = host + ":" + port;
            if (headless && GodotLspServer.isStoppedIntentionally(headlessDir)) {
                LOG.log(Level.FINE, "Headless Godot LSP at {0} is gone on purpose: {1}", new Object[]{target, ex});
                rememberForReopen(headlessDir, restarter);
                return null;
            }
            if (headless) {
                reportFailure(target + " " + ex, Bundle.MSG_LspHeadlessFailedTitle(),
                        Bundle.MSG_LspHeadlessUnreachableText(target), ex);
            } else {
                reportFailure(target + " " + ex, Bundle.MSG_LspUnreachableTitle(),
                        Bundle.MSG_LspUnreachableText(target), ex);
            }
            scheduleRetry(restarter);
            return null;
        }
    }

    /**
     * Records a failed start. INFO and a notification once per distinct failure; details at FINE afterwards.
     * A later successful connection resets it.
     */
    private static void reportFailure(String failure, String title, String text, Exception ex) {
        boolean first;
        synchronized (LOCK) {
            first = !failure.equals(lastFailure);
            lastFailure = failure;
            announceConnect = true;
            connected = false;
        }
        LOG.log(first ? Level.INFO : Level.FINE, "Godot LSP not available: " + failure, ex);
        if (first) {
            NotificationDisplayer.getDefault().notify(title, ICON, text, null, NotificationDisplayer.Priority.NORMAL);
        }
    }

    /**
     * Wraps the server's input so that the server closing the connection (EOF) or breaking it (IOException)
     * is reported to the user. A close initiated by this client closes the socket first, so it is not reported.
     * After reporting, the socket is closed so SocketProcess reports the server as dead, and a reconnect is scheduled.
     * A headless server stopped on purpose (project closed, IDE exiting) is neither reported nor reconnected.
     *
     * @param projectDir project of the headless server, null in editor mode
     * @param generation session of the project when the connection was made; a different current one means
     *                   the project was closed (and maybe reopened) since, so the loss is on purpose
     */
    private static InputStream watchForLoss(Socket socket, InputStream in, String target, ServerRestarter restarter,
            File projectDir, long generation) {
        return new InputStream() {
            private boolean reported;

            private void reportIfLost() {
                if (reported || socket.isClosed()) {
                    return;
                }
                reported = true;
                if (projectDir != null && (GodotLspServer.isStoppedIntentionally(projectDir)
                        || GodotLspServer.generation(projectDir) != generation)) {
                    synchronized (LOCK) {
                        announceConnect = true;
                        connected = false;
                    }
                    LOG.log(Level.FINE, "Headless Godot LSP at {0} was stopped on purpose", target);
                    closeQuietly(socket);
                    rememberForReopen(projectDir, restarter);
                    return;
                }
                synchronized (LOCK) {
                    announceConnect = true;
                    connected = false;
                }
                LOG.log(Level.INFO, "Lost connection to Godot LSP at {0}", target);
                NotificationDisplayer.getDefault().notify(
                        Bundle.MSG_LspLostTitle(),
                        ICON,
                        Bundle.MSG_LspLostText(target),
                        null,
                        NotificationDisplayer.Priority.NORMAL);
                // Report first (the check above relies on the socket still being open), then close.
                closeQuietly(socket);
                scheduleRetry(restarter);
            }

            @Override
            public int read() throws IOException {
                try {
                    int c = in.read();
                    if (c < 0) {
                        reportIfLost();
                    }
                    return c;
                } catch (IOException ex) {
                    reportIfLost();
                    throw ex;
                }
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                try {
                    int n = in.read(b, off, len);
                    if (n < 0) {
                        reportIfLost();
                    }
                    return n;
                } catch (IOException ex) {
                    reportIfLost();
                    throw ex;
                }
            }

            @Override
            public void close() throws IOException {
                in.close();
            }
        };
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
            PENDING_RESTART.put(GodotLspServer.keyOf(projectDir), restarter);
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
     * Restarts the LSP client through the last known restarter, e.g. after the connection settings changed.
     * Does nothing if no restarter has been seen yet (no .gd editor has started the server).
     */
    public static void reconnect() {
        ServerRestarter restarter;
        synchronized (LOCK) {
            restarter = currentRestarter;
        }
        if (restarter != null) {
            RP.post(restarter::restart);
        }
    }

    private static void scheduleRetry(ServerRestarter restarter) {
        if (!GodotSettings.isAutoReconnect()) {
            return;
        }
        synchronized (LOCK) {
            if (restarter != null) {
                currentRestarter = restarter;
            }
            if (currentRestarter == null) {
                return;
            }
            if (retryTask == null) {
                retryTask = RP.create(GdscriptClient::retry);
            }
            if (retryTask.isFinished()) {
                retryStart = 0;
                retryTask.schedule(GodotSettings.getRetryIntervalMs());
            }
        }
    }

    private static void retry() {
        ServerRestarter restarter;
        synchronized (LOCK) {
            restarter = currentRestarter;
            if (restarter == null || connected) {
                return; // somebody (e.g. lsp.client itself) already reconnected
            }
            if (retryStart == 0) {
                retryStart = System.nanoTime();
            } else if (System.nanoTime() - retryStart > RETRY_GIVE_UP_NANOS) {
                retryStart = 0;
                LOG.log(Level.INFO, "Giving up reconnecting to the Godot LSP; it will be retried when a .gd file is opened");
                return;
            }
        }
        if (GodotSettings.isHeadlessLsp()) {
            if (GodotLspServer.isShuttingDown()) {
                return;
            }
            // Nothing to probe: startServer starts the headless server itself and backs off after a failure.
            restarter.restart();
            return;
        }
        String host = GodotSettings.getHost();
        int port = GodotSettings.getPort();
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(host, port), GodotSettings.getConnectTimeoutMs());
        } catch (IOException ex) {
            if (GodotSettings.isAutoReconnect()) {
                synchronized (LOCK) {
                    retryTask.schedule(GodotSettings.getRetryIntervalMs());
                }
            }
            return;
        }
        synchronized (LOCK) {
            retryStart = 0;
        }
        LOG.log(Level.INFO, "Godot LSP reachable at {0}:{1}, restarting client", new Object[]{host, port});
        restarter.restart();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ex) {
            LOG.log(Level.FINEST, null, ex);
        }
    }

    /**
     * Fake process wrapping the socket so lsp.client can detect a dead server
     * (isAlive) and close the connection (destroy).
     */
    private static final class SocketProcess extends Process {

        private final Socket socket;

        SocketProcess(Socket socket) {
            this.socket = socket;
        }

        private boolean closed() {
            return socket.isClosed() || !socket.isConnected()
                    || socket.isInputShutdown() || socket.isOutputShutdown();
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            while (!closed()) {
                Thread.sleep(500);
            }
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            long end = System.nanoTime() + unit.toNanos(timeout);
            while (!closed()) {
                if (System.nanoTime() >= end) {
                    return false;
                }
                Thread.sleep(100);
            }
            return true;
        }

        @Override
        public int exitValue() {
            if (!closed()) {
                throw new IllegalThreadStateException("Socket still open");
            }
            return 0;
        }

        @Override
        public boolean isAlive() {
            return !closed();
        }

        @Override
        public void destroy() {
            closeQuietly(socket);
        }
    }
}
