package io.ziumper.godot.lsp;

import io.ziumper.godot.lsp.process.FirstResponseWatchdog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One connection to a Godot LSP server. Watches the server's input for the end of the connection and for the
 * first response, and tells its {@link Listener} once, with the cause, when the connection ends. Poses as the
 * server's {@link Process}, which is what lsp.client needs to see a dead server ({@link #isAlive()}) and to drop
 * the connection ({@link #destroy()}).
 */
final class LspConnection extends Process {

    private static final Logger LOG = Logger.getLogger(LspConnection.class.getName());

    /** Why a connection ended. Exactly one cause is recorded, the first one. */
    enum CloseCause {
        /** We closed it: lsp.client destroyed the "process" (restart, server stop, project close, IDE exit). */
        REQUESTED,
        /** The server closed it (EOF) or it broke (read error). */
        LOST,
        /** The server accepted the connection but did not answer within the first-response timeout. */
        NO_RESPONSE
    }

    /** Told once when the connection ends, and once when the server first answers. */
    @FunctionalInterface
    interface Listener {
        void ended(LspConnection connection, CloseCause cause);

        /** The first byte arrived from the server; called at most once, on the reading thread. */
        default void firstResponse(LspConnection connection) {
        }
    }

    /** The two-way byte channel under the connection; a socket in production. */
    interface Transport {
        InputStream in();

        OutputStream out();

        boolean isOpen();

        /** Closes quietly; idempotent. */
        void close();
    }

    private final Transport transport;
    private final FirstResponseWatchdog watchdog;
    private final String target;
    private final Listener listener;
    private final AtomicReference<CloseCause> cause = new AtomicReference<>();
    private final InputStream input;
    private final AtomicBoolean answered = new AtomicBoolean();
    /** For the log line of a NO_RESPONSE end. */
    private volatile long firstResponseTimeoutMs;

    LspConnection(Transport transport, FirstResponseWatchdog.Scheduler scheduler, String target, Listener listener) {
        this.transport = transport;
        this.watchdog = new FirstResponseWatchdog(scheduler);
        this.target = target;
        this.listener = listener;
        this.input = new WatchedInput(transport.in());
    }

    /** Connects to {@code host:port}; throws if it cannot, leaving nothing open. */
    static LspConnection connect(String host, int port, int timeoutMs, FirstResponseWatchdog.Scheduler scheduler,
            Listener listener) throws IOException {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            Transport t = new SocketTransport(socket, socket.getInputStream(), socket.getOutputStream());
            return new LspConnection(t, scheduler, host + ":" + port, listener);
        } catch (IOException ex) {
            SocketTransport.closeQuietly(socket);
            throw ex;
        }
    }

    /** Server-to-client stream; its end is the end of the connection. */
    InputStream input() {
        return input;
    }

    /** Client-to-server stream. */
    OutputStream output() {
        return transport.out();
    }

    /** Ends the connection as NO_RESPONSE if no byte arrives within the timeout. */
    void watchFirstResponse(long timeoutMs) {
        firstResponseTimeoutMs = timeoutMs;
        watchdog.arm(timeoutMs, () -> end(CloseCause.NO_RESPONSE));
    }

    /** Why the connection ended; empty while it is open. */
    Optional<CloseCause> closeCause() {
        return Optional.ofNullable(cause.get());
    }

    /** Tells the listener once about the first byte, unless the connection has already ended. */
    private void answered() {
        watchdog.onFirstByte();
        if (cause.get() == null && answered.compareAndSet(false, true)) {
            listener.firstResponse(this);
        }
    }

    /** Records the first cause only; closes the transport, then tells the listener (only for the first cause). */
    private void end(CloseCause c) {
        watchdog.disarm();
        boolean first = cause.compareAndSet(null, c);
        if (first && c == CloseCause.NO_RESPONSE) {
            LOG.log(Level.INFO, "No response from Godot LSP at {0} within {1} s; closing the connection",
                    new Object[]{target, firstResponseTimeoutMs / 1000});
        }
        transport.close();
        if (first) {
            listener.ended(this, c);
        }
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
        while (transport.isOpen()) {
            Thread.sleep(500);
        }
        return 0;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        long end = System.nanoTime() + unit.toNanos(timeout);
        while (transport.isOpen()) {
            if (System.nanoTime() >= end) {
                return false;
            }
            Thread.sleep(100);
        }
        return true;
    }

    @Override
    public int exitValue() {
        if (transport.isOpen()) {
            throw new IllegalThreadStateException("Socket still open");
        }
        return 0;
    }

    /** True while the socket is open. */
    @Override
    public boolean isAlive() {
        return transport.isOpen();
    }

    /** lsp.client drops the connection on purpose: restart, server stop, project close or IDE exit. */
    @Override
    public void destroy() {
        end(CloseCause.REQUESTED);
    }

    /**
     * The server's input. Its end (EOF) or a read error ends the connection as LOST, unless it already ended
     * (then the error is just the consequence of our own close). The first byte disarms the watchdog.
     */
    private final class WatchedInput extends InputStream {

        private final InputStream in;

        WatchedInput(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            try {
                int c = in.read();
                if (c < 0) {
                    end(CloseCause.LOST);
                } else {
                    answered();
                }
                return c;
            } catch (IOException ex) {
                end(CloseCause.LOST);
                throw ex;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                int n = in.read(b, off, len);
                if (n < 0) {
                    end(CloseCause.LOST);
                } else if (n > 0) {
                    answered();
                }
                return n;
            } catch (IOException ex) {
                end(CloseCause.LOST);
                throw ex;
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private static final class SocketTransport implements Transport {

        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        SocketTransport(Socket socket, InputStream in, OutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        @Override
        public InputStream in() {
            return in;
        }

        @Override
        public OutputStream out() {
            return out;
        }

        @Override
        public boolean isOpen() {
            return !(socket.isClosed() || !socket.isConnected()
                    || socket.isInputShutdown() || socket.isOutputShutdown());
        }

        @Override
        public void close() {
            closeQuietly(socket);
        }

        static void closeQuietly(Socket s) {
            try {
                s.close();
            } catch (IOException ex) {
                LOG.log(Level.FINEST, null, ex);
            }
        }
    }
}
