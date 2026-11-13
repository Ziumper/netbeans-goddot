package io.ziumper.godot.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ziumper.godot.lsp.LspConnection.CloseCause;
import io.ziumper.godot.lsp.process.FirstResponseWatchdog;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LspConnectionTest {

    private final ManualScheduler scheduler = new ManualScheduler();
    private final FakeTransport transport = new FakeTransport();
    private final List<CloseCause> ends = new ArrayList<>();
    private final AtomicInteger answers = new AtomicInteger();
    private final LspConnection connection = new LspConnection(transport, scheduler, "127.0.0.1:6010",
            new LspConnection.Listener() {
                @Override
                public void ended(LspConnection c, CloseCause cause) {
                    ends.add(cause);
                }

                @Override
                public void firstResponse(LspConnection c) {
                    answers.incrementAndGet();
                }
            });

    @Test
    void firstByteIsReportedOnceAcrossReadsAndVariants() throws IOException {
        transport.feed("a");
        transport.feed("b");
        transport.feed("c");
        connection.watchFirstResponse(30_000);

        assertEquals('a', connection.input().read());
        assertEquals(1, answers.get());
        assertEquals(1, connection.input().read(new byte[2], 0, 2));
        assertEquals('c', connection.input().read());
        assertEquals(1, answers.get());
    }

    @Test
    void firstByteViaArrayReadIsReported() throws IOException {
        transport.feed("x");

        assertEquals(1, connection.input().read(new byte[4], 0, 4));
        assertEquals(1, answers.get());
    }

    @Test
    void noByteAndTimeoutIsNotAResponse() {
        connection.watchFirstResponse(30_000);
        scheduler.fire();

        assertEquals(List.of(CloseCause.NO_RESPONSE), ends);
        assertEquals(0, answers.get());
    }

    @Test
    void endOfStreamIsNotAResponse() throws IOException {
        transport.endOfStream();

        assertEquals(-1, connection.input().read(new byte[8], 0, 8));
        assertEquals(0, answers.get());
    }

    @Test
    void destroyBeforeTheFirstByteIsNotAResponse() {
        connection.watchFirstResponse(30_000);
        connection.destroy();

        assertThrows(IOException.class, () -> connection.input().read());
        assertEquals(0, answers.get());
    }

    @Test
    void firstByteDisarmsTheWatchdog() throws IOException {
        transport.feed("C");
        connection.watchFirstResponse(30_000);

        assertEquals('C', connection.input().read());
        scheduler.fire();

        assertTrue(ends.isEmpty());
        assertTrue(connection.isAlive());
        assertEquals(Optional.empty(), connection.closeCause());
    }

    @Test
    void watchdogTimeoutIsALossReportedOnce() {
        connection.watchFirstResponse(30_000);
        scheduler.fire();

        assertEquals(List.of(CloseCause.NO_RESPONSE), ends);
        assertFalse(connection.isAlive());
        // The reader then fails on the closed socket and lsp.client drops the dead server: no second report.
        assertThrows(IOException.class, () -> connection.input().read(new byte[8], 0, 8));
        connection.destroy();
        assertEquals(List.of(CloseCause.NO_RESPONSE), ends);
    }

    @Test
    void destroyIsAClosingOnPurposeNotALoss() {
        connection.watchFirstResponse(30_000);
        connection.destroy();

        assertEquals(List.of(CloseCause.REQUESTED), ends);
        assertFalse(connection.isAlive());
        scheduler.fire(); // the timeout was cancelled
        assertEquals(List.of(CloseCause.REQUESTED), ends);
    }

    @Test
    void endOfStreamIsALossReportedOnce() throws IOException {
        transport.endOfStream();
        connection.watchFirstResponse(30_000);

        assertEquals(-1, connection.input().read(new byte[8], 0, 8));
        assertEquals(List.of(CloseCause.LOST), ends);
        assertFalse(connection.isAlive());

        assertThrows(IOException.class, () -> connection.input().read());
        connection.destroy();
        scheduler.fire();
        assertEquals(List.of(CloseCause.LOST), ends);
    }

    @Test
    void readErrorIsALoss() {
        transport.fail(new SocketException("Connection reset"));

        assertThrows(SocketException.class, () -> connection.input().read());
        assertEquals(List.of(CloseCause.LOST), ends);
        assertFalse(connection.isAlive());
    }

    @Test
    void readErrorAfterDestroyIsNotALoss() {
        connection.destroy();

        assertThrows(IOException.class, () -> connection.input().read(new byte[8], 0, 8));
        assertEquals(List.of(CloseCause.REQUESTED), ends);
    }

    @Test
    void posesAsAProcessThatEndsWithTheSocket() throws InterruptedException {
        assertThrows(IllegalThreadStateException.class, connection::exitValue);
        assertFalse(connection.waitFor(0, TimeUnit.MILLISECONDS));

        connection.destroy();

        assertEquals(0, connection.waitFor());
        assertTrue(connection.waitFor(0, TimeUnit.MILLISECONDS));
        assertEquals(0, connection.exitValue());
    }

    /** Tasks run only on {@link #fire()}; cancelled ones never. */
    private static final class ManualScheduler implements FirstResponseWatchdog.Scheduler {

        private final List<Runnable> pending = new ArrayList<>();

        @Override
        public FirstResponseWatchdog.Cancellable schedule(Runnable task, long delayMs) {
            pending.add(task);
            return () -> pending.remove(task);
        }

        void fire() {
            List<Runnable> now = new ArrayList<>(pending);
            pending.clear();
            now.forEach(Runnable::run);
        }
    }

    /** A scripted socket: chunks, then EOF or an error; reads fail once it is closed, as with a real socket. */
    private static final class FakeTransport implements LspConnection.Transport {

        private static final Object EOF = new Object();
        private final Deque<Object> script = new ArrayDeque<>();
        private boolean open = true;

        private final InputStream in = new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (!open) {
                    throw new SocketException("Socket closed");
                }
                Object next = script.poll();
                if (next == null) {
                    throw new AssertionError("read without scripted input");
                }
                if (next == EOF) {
                    return -1;
                }
                if (next instanceof IOException ex) {
                    throw ex;
                }
                byte[] chunk = (byte[]) next;
                System.arraycopy(chunk, 0, b, off, chunk.length);
                return chunk.length;
            }
        };

        void feed(String s) {
            script.add(s.getBytes(StandardCharsets.US_ASCII));
        }

        void endOfStream() {
            script.add(EOF);
        }

        void fail(IOException ex) {
            script.add(ex);
        }

        @Override
        public InputStream in() {
            return in;
        }

        @Override
        public OutputStream out() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
