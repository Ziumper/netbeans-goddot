package io.ziumper.godot.lsp.process;

import static io.ziumper.godot.lsp.process.ServerFixture.EXE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ziumper.godot.lsp.process.HeadlessServer.State;
import io.ziumper.godot.lsp.process.ServerFixture.FakeLauncher.Launch;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class HeadlessServerTest {

    @TempDir
    Path tmp;
    private ServerFixture f;
    private File dir;
    private HeadlessServer server;

    @BeforeEach
    void setUp() throws IOException {
        f = new ServerFixture(tmp);
        dir = f.project("my game");
        server = f.registry.forProject(dir);
    }

    @Test
    void startsOneProcessAndBecomesReady() throws IOException {
        assertEquals(State.IDLE, server.state());
        assertEquals(6010, server.ensureReady(EXE));

        assertEquals(State.READY, server.state());
        assertEquals(1, f.launcher.launches.size());
        Launch l = f.launcher.launches.get(0);
        assertEquals(EXE, l.exe());
        assertEquals(6010, l.port());
        assertEquals(tmp.resolve("godot-lsp-my_game-6010.log").toFile(), l.log());
        assertEquals(l.process().pid(), PidFile.read(server.pidPath(6010)).orElseThrow().pid());
        assertEquals(6010, f.store.load(dir));
        assertEquals(6010, server.port());
        assertEquals(1, f.hooks.size());
        assertEquals(0, f.ticker.sleeps());

        assertEquals(6010, server.ensureReady(EXE)); // attaches to the running server
        assertEquals(1, f.launcher.launches.size());
    }

    @Test
    void readyPortIsKnownOnlyWhileTheOwnProcessIsUpAndNeverStartsOne() throws IOException {
        assertEquals(-1, server.readyPort());
        assertTrue(f.launcher.launches.isEmpty());

        f.launcher.onLaunch = l -> {
        };
        f.ticker.onSleep = () -> {
            assertEquals(-1, server.readyPort()); // launched, port not open yet
            f.ports.listen(6010, f.launched(0));
        };
        server.ensureReady(EXE);
        assertEquals(6010, server.readyPort());

        f.launched(0).die();
        assertEquals(-1, server.readyPort());
        assertEquals(State.READY, server.state()); // a dead process is dropped by the next start, not here
    }

    @Test
    void readyPortIsUnknownOnceTheProjectIsClosedOrTheIdeExits() throws IOException {
        server.ensureReady(EXE);
        server.close();
        assertEquals(-1, server.readyPort());

        server.allow();
        f.scheduler.runPending();
        server.ensureReady(EXE);
        assertEquals(6010, server.readyPort());

        f.registry.stopAll(StopReason.IDE_EXIT);
        assertEquals(-1, server.readyPort());
    }

    @Test
    @Timeout(10)
    void parallelStartsShareOneProcess() throws Exception {
        f.launcher.onLaunch = l -> {
        }; // the port opens only when the test says so
        AtomicInteger secondPort = new AtomicInteger();
        AtomicReference<Throwable> secondError = new AtomicReference<>();
        Thread second = new Thread(() -> {
            try {
                secondPort.set(server.ensureReady(EXE));
            } catch (Throwable ex) {
                secondError.set(ex);
            }
        });
        f.ticker.onFirstSleep(() -> {
            // The first start holds the project's start lock while it waits for the port.
            second.start();
            while (second.getState() != Thread.State.BLOCKED && second.getState() != Thread.State.TERMINATED) {
                Thread.onSpinWait();
            }
            f.ports.listen(6010, f.launched(0));
        });

        assertEquals(6010, server.ensureReady(EXE));
        second.join();

        assertNull(secondError.get());
        assertEquals(6010, secondPort.get());
        assertEquals(1, f.launcher.launches.size());
        assertEquals(State.READY, server.state());
    }

    @Test
    void portThatNeverOpensFailsAfterTheTimeoutAndBacksOff() throws IOException {
        f.launcher.onLaunch = l -> {
        };
        IOException first = assertThrows(IOException.class, () -> server.ensureReady(EXE));
        assertTrue(first.getMessage().startsWith("Godot LSP did not open port 6010 within 30 s"), first.getMessage());
        assertEquals(120, f.ticker.sleeps()); // 30 s in 250 ms polls, none of them real
        assertEquals(State.STARTING, server.state()); // still alive, maybe importing

        IOException refused = assertThrows(IOException.class, () -> server.ensureReady(EXE));
        assertFalse(refused instanceof StartRefusedException);
        assertEquals(first.getMessage(), refused.getMessage());
        assertEquals(120, f.ticker.sleeps()); // refused without waiting
        assertEquals(1, f.launcher.launches.size());

        f.ticker.advance(30_000);
        f.ports.listen(6010, f.launched(0)); // Godot is done importing
        assertEquals(6010, server.ensureReady(EXE));
        assertEquals(1, f.launcher.launches.size()); // the same process was waited for again
        assertEquals(State.READY, server.state());
    }

    @Test
    void processThatExitsBeforeThePortOpensFailsWithItsExitCode() throws IOException {
        f.launcher.onLaunch = l -> {
            l.process().exitCode = 3;
            l.process().die();
        };
        IOException ex = assertThrows(IOException.class, () -> server.ensureReady(EXE));
        assertTrue(ex.getMessage().startsWith("Godot exited with code 3 before the LSP came up, see "), ex.getMessage());
        assertFalse(Files.exists(server.pidPath(6010))); // removed when the process exited

        assertThrows(IOException.class, () -> server.ensureReady(EXE)); // backoff
        assertEquals(1, f.launcher.launches.size());

        f.ticker.advance(30_000);
        f.launcher.onLaunch = l -> f.ports.listen(l.port(), l.process());
        assertEquals(6010, server.ensureReady(EXE));
        assertEquals(2, f.launcher.launches.size()); // the dead one is replaced
        assertEquals(State.READY, server.state());
    }

    @Test
    void closeWhileWaitingForThePortRefusesAndKillsTheProcess() {
        f.launcher.onLaunch = l -> {
        };
        f.ticker.onFirstSleep(server::close);

        StartRefusedException ex = assertThrows(StartRefusedException.class, () -> server.ensureReady(EXE));
        assertEquals("project closed", ex.getMessage());
        FakeGodotProcess p = f.launched(0);
        assertTrue(p.isAlive()); // close() kills off the caller's thread
        assertEquals(State.STOPPING, server.state());

        f.scheduler.runPending();
        assertEquals(List.of("destroy"), p.calls);
        assertEquals(State.IDLE, server.state());
        assertFalse(Files.exists(server.pidPath(6010)));
    }

    @Test
    void closeBetweenLaunchAndPublicationKillsTheOwnProcess() {
        f.launcher.onLaunch = l -> server.close();

        StartRefusedException ex = assertThrows(StartRefusedException.class, () -> server.ensureReady(EXE));
        assertEquals("project closed", ex.getMessage());
        assertEquals(List.of("destroy"), f.launched(0).calls); // killed by the start itself
        assertTrue(f.scheduler.snapshot().isEmpty()); // close() never saw it
        assertEquals(State.IDLE, server.state());
    }

    @Test
    void closeAfterReadyDetachesTheProcessAndTheNextStartWaitsForIt() throws IOException {
        server.ensureReady(EXE);
        FakeGodotProcess old = f.launched(0);
        long generation = server.generation();

        server.close();
        assertTrue(server.isStoppedIntentionally());
        assertEquals(generation + 1, server.generation());
        assertEquals(State.STOPPING, server.state());
        assertTrue(old.isAlive()); // not killed on the caller's thread

        server.allow();
        f.ticker.onSleep = f.scheduler::runPending; // the kill completes while the new start waits
        f.launcher.onLaunch = l -> {
            assertFalse(old.isAlive(), "started before the closing server was gone");
            f.ports.listen(l.port(), l.process());
        };
        assertEquals(6010, server.ensureReady(EXE));

        assertEquals(List.of("destroy"), old.calls);
        assertTrue(f.ticker.sleeps() > 0);
        assertEquals(2, f.launcher.launches.size());
        assertEquals(6010, f.launcher.launches.get(1).port()); // same port, not a foreign holder
        assertEquals(State.READY, server.state());
    }

    @Test
    void closedProjectStartsAgainOnlyAfterAllow() throws IOException {
        server.close();
        assertThrows(StartRefusedException.class, () -> server.ensureReady(EXE));
        assertTrue(f.launcher.launches.isEmpty());

        server.allow();
        assertFalse(server.isStoppedIntentionally());
        assertEquals(6010, server.ensureReady(EXE));
        assertEquals(1, f.launcher.launches.size());
    }

    @Test
    void settingsChangeKillsTheServerAndEndsTheSessionButKeepsTheProjectOpen() throws IOException {
        server.ensureReady(EXE);
        FakeGodotProcess old = f.launched(0);
        long generation = server.generation();

        server.stop(StopReason.SETTINGS_CHANGED);
        assertEquals(generation + 1, server.generation());
        assertFalse(server.isStoppedIntentionally());
        assertTrue(old.isAlive()); // not killed on the caller's thread

        f.scheduler.runPending();
        assertEquals(List.of("destroy"), old.calls);
        assertEquals(State.IDLE, server.state());
        assertEquals(6010, server.ensureReady(EXE));
        assertEquals(2, f.launcher.launches.size());
    }

    @Test
    void settingsChangeDuringAStartRefusesIt() {
        f.launcher.onLaunch = l -> {
        };
        f.ticker.onFirstSleep(() -> server.stop(StopReason.SETTINGS_CHANGED));

        assertThrows(StartRefusedException.class, () -> server.ensureReady(EXE));
        f.scheduler.runPending();
        assertEquals(List.of("destroy"), f.launched(0).calls);
        assertFalse(server.isStoppedIntentionally());
    }

    @Test
    void staleGodotOfThisProjectIsKilledAndItsPortKept() throws IOException {
        f.store.save(dir, 6010);
        FakeGodotProcess stale = new FakeGodotProcess(4242, "/opt/godot/Godot_v4.3-stable_linux.x86_64");
        f.running.put(4242L, stale);
        f.ports.listen(6010, stale);
        new PidFile(4242, 6010, dir.getAbsolutePath(), 0).write(server.pidPath(6010));

        assertEquals(6010, server.ensureReady(EXE));

        assertEquals(List.of("destroy"), stale.calls);
        assertEquals(6010, f.launcher.launches.get(0).port());
        assertEquals(6010, f.store.load(dir));
        assertEquals(f.launched(0).pid(), PidFile.read(server.pidPath(6010)).orElseThrow().pid());
    }

    @Test
    void foreignProcessOnTheOwnPortIsLeftAloneAndThePortMoves() throws IOException {
        f.store.save(dir, 6010);
        FakeGodotProcess foreign = new FakeGodotProcess(4242, "/usr/bin/java");
        f.running.put(4242L, foreign);
        f.ports.listen(6010, foreign);
        new PidFile(4242, 6010, dir.getAbsolutePath(), 0).write(server.pidPath(6010));

        assertEquals(6011, server.ensureReady(EXE));

        assertTrue(foreign.calls.isEmpty());
        assertTrue(foreign.isAlive());
        assertEquals(6011, f.launcher.launches.get(0).port());
        assertEquals(6011, f.store.load(dir));
    }

    @Test
    void tooOldGodotIsRefusedNamingTheMinimumVersion() {
        f.version = "4.1";
        IOException ex = assertThrows(IOException.class, () -> server.ensureReady(EXE));
        assertEquals("Godot 4.1 is too old for the headless LSP (needs 4.2 or newer)", ex.getMessage());
        assertTrue(f.launcher.launches.isEmpty());
    }

    @Test
    void undetectableVersionIsRefusedNamingTheMinimumVersion() {
        f.version = null;
        IOException ex = assertThrows(IOException.class, () -> server.ensureReady(EXE));
        assertTrue(ex.getMessage().startsWith("Could not detect the Godot version of " + EXE), ex.getMessage());
        assertTrue(ex.getMessage().contains("needs Godot 4.2 or newer"), ex.getMessage());
        assertTrue(f.launcher.launches.isEmpty());
    }

    @Test
    void missingExecutableIsRefusedBeforeAnythingElse() {
        f.launcher.executable = false;
        IOException ex = assertThrows(IOException.class, () -> server.ensureReady(EXE));
        assertEquals("Godot executable not found or not executable: " + EXE, ex.getMessage());
        assertEquals(0, f.versionChecks.get());
        assertTrue(f.launcher.launches.isEmpty());
    }

    @Test
    void nothingStartsWhileTheIdeExits() {
        f.registry.stopAll(StopReason.IDE_EXIT);

        StartRefusedException ex = assertThrows(StartRefusedException.class, () -> server.ensureReady(EXE));
        assertEquals("IDE is shutting down", ex.getMessage());
        assertTrue(server.isStoppedIntentionally());
        assertEquals(0, f.versionChecks.get());
        assertTrue(f.launcher.launches.isEmpty());
    }

    @Test
    void ideExitRightAfterTheLaunchKillsTheOwnProcess() {
        f.launcher.onLaunch = l -> f.registry.stopAll(StopReason.IDE_EXIT);

        StartRefusedException ex = assertThrows(StartRefusedException.class, () -> server.ensureReady(EXE));
        assertEquals("IDE is shutting down", ex.getMessage());
        assertEquals(List.of("destroy"), f.launched(0).calls);
    }

    @Test
    void onTheEdtItOnlyWarns() throws Exception {
        Logger log = Logger.getLogger(HeadlessServer.class.getName());
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                records.add(r);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.addHandler(handler);
        boolean parents = log.getUseParentHandlers();
        log.setUseParentHandlers(false); // the warning carries a stack trace; keep it off the console
        try {
            AtomicInteger port = new AtomicInteger();
            AtomicReference<Exception> error = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    port.set(server.ensureReady(EXE));
                } catch (IOException ex) {
                    error.set(ex);
                }
            });
            assertNull(error.get());
            assertEquals(6010, port.get());
            assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING
                    && r.getMessage().contains("on the EDT")));
        } finally {
            log.removeHandler(handler);
            log.setUseParentHandlers(parents);
        }
    }
}
