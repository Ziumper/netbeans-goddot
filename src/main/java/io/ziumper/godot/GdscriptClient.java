package io.ziumper.godot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.api.editor.mimelookup.MimeRegistration;
import org.netbeans.modules.lsp.client.spi.LanguageServerProvider;
import org.netbeans.modules.lsp.client.spi.ServerRestarter;
import org.netbeans.api.project.Project;
import org.openide.util.Lookup;
import org.openide.util.RequestProcessor;

/**
 * Connects GDScript editors directly to the Godot Editor LSP server over TCP.
 */
@MimeRegistration(mimeType = "text/gdscript", service = LanguageServerProvider.class)
public class GdscriptClient implements LanguageServerProvider {

    private static final Logger LOG = Logger.getLogger(GdscriptClient.class.getName());
    private static final RequestProcessor RP = new RequestProcessor(GdscriptClient.class);

    /** The single pending retry task; guarantees one retry loop at a time. */
    private static RequestProcessor.Task retryTask;
    private static final Object LOCK = new Object();

    /** Last failure message logged, so repeated identical failures do not flood messages.log. */
    private static String lastFailure;

    @Override
    public LanguageServerDescription startServer(Lookup lkp) {
        ServerRestarter restarter = lkp.lookup(ServerRestarter.class);
        String host = GodotLspSettings.getHost();
        int port = GodotLspSettings.getPort();

        Project prj = lkp.lookup(Project.class);
        LOG.log(Level.INFO, "startServer called for Godot LSP: project={0}, target={1}:{2}",
                new Object[]{prj != null ? prj.getProjectDirectory().getPath() : "<none>", host, port});

        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.connect(new InetSocketAddress(host, port), GodotLspSettings.getConnectTimeoutMs());
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            synchronized (LOCK) {
                lastFailure = null;
            }
            LOG.log(Level.INFO, "Connected to Godot LSP at {0}:{1}", new Object[]{host, port});
            return LanguageServerDescription.create(in, out, new SocketProcess(socket));
        } catch (IOException ex) {
            closeQuietly(socket);
            String failure = host + ":" + port + " " + ex;
            boolean first;
            synchronized (LOCK) {
                first = !failure.equals(lastFailure);
                lastFailure = failure;
            }
            // INFO once per distinct failure; details at FINE afterwards.
            LOG.log(first ? Level.INFO : Level.FINE, "Godot LSP not reachable at " + host + ":" + port
                    + " (is the Godot editor running with the LSP enabled?)", ex);
            scheduleRetry(restarter);
            return null;
        }
    }

    private static void scheduleRetry(ServerRestarter restarter) {
        if (restarter == null || !GodotLspSettings.isAutoReconnect()) {
            return;
        }
        synchronized (LOCK) {
            if (retryTask != null && !retryTask.isFinished()) {
                return; // a retry is already pending
            }
            if (retryTask == null) {
                retryTask = RP.create(() -> retry(restarter));
            }
            retryTask.schedule(GodotLspSettings.getRetryIntervalMs());
        }
    }

    private static void retry(ServerRestarter restarter) {
        String host = GodotLspSettings.getHost();
        int port = GodotLspSettings.getPort();
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(host, port), GodotLspSettings.getConnectTimeoutMs());
        } catch (IOException ex) {
            if (GodotLspSettings.isAutoReconnect()) {
                synchronized (LOCK) {
                    retryTask.schedule(GodotLspSettings.getRetryIntervalMs());
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
