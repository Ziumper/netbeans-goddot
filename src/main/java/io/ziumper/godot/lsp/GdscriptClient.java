package io.ziumper.godot.lsp;

import io.ziumper.godot.GodotIcons;
import io.ziumper.godot.options.GodotSettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    "MSG_LspLostTitle=Godot LSP disconnected",
    "MSG_LspLostText=The connection to the Godot LSP at {0} was lost. Completion and diagnostics stop until it reconnects."
})
@MimeRegistration(mimeType = "text/gdscript", service = LanguageServerProvider.class)
public class GdscriptClient implements LanguageServerProvider {

    private static final Logger LOG = Logger.getLogger(GdscriptClient.class.getName());
    private static final RequestProcessor RP = new RequestProcessor(GdscriptClient.class);

    /** The single pending retry task; guarantees one retry loop at a time. */
    private static RequestProcessor.Task retryTask;
    /** languageId Godot's LSP server recognises. */
    static final String LANGUAGE_ID = "gdscript";
    private static final Object LOCK = new Object();
    /** Notifications require a non-null icon. */
    private static final Icon ICON = ImageUtilities.loadImageIcon(GodotIcons.GODOT_ICON, false);

    /** Last failure message logged, so repeated identical failures do not flood messages.log. */
    private static String lastFailure;
    /** True until a connection is announced; set again after a failure or a lost connection. Guarded by LOCK. */
    private static boolean announceConnect = true;

    @Override
    public LanguageServerDescription startServer(Lookup lkp) {
        ServerRestarter restarter = lkp.lookup(ServerRestarter.class);
        String host = GodotSettings.getHost();
        int port = GodotSettings.getPort();

        Project prj = lkp.lookup(Project.class);
        LOG.log(Level.INFO, "startServer called for Godot LSP: project={0}, target={1}:{2}",
                new Object[]{prj != null ? prj.getProjectDirectory().getPath() : "<none>", host, port});

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
            return createDescription(new GodotNotificationFilter(watchForLoss(socket, in, target)), out,
                    new SocketProcess(socket));
        } catch (IOException ex) {
            closeQuietly(socket);
            String failure = host + ":" + port + " " + ex;
            boolean first;
            synchronized (LOCK) {
                first = !failure.equals(lastFailure);
                lastFailure = failure;
                announceConnect = true;
            }
            // INFO once per distinct failure; details at FINE afterwards.
            LOG.log(first ? Level.INFO : Level.FINE, "Godot LSP not reachable at " + host + ":" + port
                    + " (is the Godot editor running with the LSP enabled?)", ex);
            if (first) {
                // Shown once per distinct failure; a later successful connection resets it.
                NotificationDisplayer.getDefault().notify(
                        Bundle.MSG_LspUnreachableTitle(),
                        ICON,
                        Bundle.MSG_LspUnreachableText(host + ":" + port),
                        null,
                        NotificationDisplayer.Priority.NORMAL);
            }
            scheduleRetry(restarter);
            return null;
        }
    }

    /**
     * Wraps the server's input so that the server closing the connection (EOF) or breaking it (IOException)
     * is reported to the user. A close initiated by this client closes the socket first, so it is not reported.
     */
    private static InputStream watchForLoss(Socket socket, InputStream in, String target) {
        return new InputStream() {
            private boolean reported;

            private void reportIfLost() {
                if (reported || socket.isClosed()) {
                    return;
                }
                reported = true;
                synchronized (LOCK) {
                    announceConnect = true;
                }
                LOG.log(Level.INFO, "Lost connection to Godot LSP at {0}", target);
                NotificationDisplayer.getDefault().notify(
                        Bundle.MSG_LspLostTitle(),
                        ICON,
                        Bundle.MSG_LspLostText(target),
                        null,
                        NotificationDisplayer.Priority.NORMAL);
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

    private static void scheduleRetry(ServerRestarter restarter) {
        if (restarter == null || !GodotSettings.isAutoReconnect()) {
            return;
        }
        synchronized (LOCK) {
            if (retryTask != null && !retryTask.isFinished()) {
                return; // a retry is already pending
            }
            if (retryTask == null) {
                retryTask = RP.create(() -> retry(restarter));
            }
            retryTask.schedule(GodotSettings.getRetryIntervalMs());
        }
    }

    private static void retry(ServerRestarter restarter) {
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
