package io.ziumper.godot.lsp.process;

import static io.ziumper.godot.lsp.process.ServerFixture.EXE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ziumper.godot.lsp.process.HeadlessServer.State;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerRegistryTest {

    @TempDir
    Path tmp;
    private ServerFixture f;

    @BeforeEach
    void setUp() {
        f = new ServerFixture(tmp);
    }

    private HeadlessServer started(String name) throws IOException {
        HeadlessServer s = f.registry.forProject(f.project(name));
        s.ensureReady(EXE);
        return s;
    }

    @Test
    void oneServerPerProjectDirectory() throws IOException {
        File a = f.project("a");
        assertSame(f.registry.forProject(a), f.registry.forProject(new File(a.getAbsolutePath())));
        assertNotSame(f.registry.forProject(a), f.registry.forProject(f.project("b")));
    }

    @Test
    void projectsGetTheirOwnFreePortsAndKeepThem() throws IOException {
        File a = f.project("a");
        File b = f.project("b");
        f.ports.takeForeign(6011);

        assertEquals(6010, f.registry.portFor(a));
        assertEquals(6012, f.registry.portFor(b)); // 6010 is a's, 6011 is busy
        assertEquals(6010, f.registry.portFor(a));
        assertEquals(Map.of(a.getAbsolutePath(), 6010, b.getAbsolutePath(), 6012), f.store.ports);
    }

    @Test
    void ideExitStopsEveryServerAndRefusesLaterStarts() throws IOException {
        HeadlessServer a = started("a");
        HeadlessServer b = started("b");
        HeadlessServer c = started("c");
        c.close(); // its kill is still pending in the background

        f.registry.stopAll(StopReason.IDE_EXIT);

        assertTrue(f.registry.isShuttingDown());
        for (int i = 0; i < 3; i++) {
            assertEquals(List.of("destroy"), f.launched(i).calls);
        }
        for (HeadlessServer s : List.of(a, b, c)) {
            assertEquals(State.IDLE, s.state());
            assertTrue(s.isStoppedIntentionally());
        }
        assertFalse(Files.exists(a.pidPath(6010)));
        assertThrows(StartRefusedException.class, () -> a.ensureReady(EXE));
        assertEquals(3, f.launcher.launches.size());
    }

    @Test
    void settingsChangeStopsAllServersInOneBackgroundTask() throws IOException {
        HeadlessServer a = started("a");
        HeadlessServer b = started("b");
        HeadlessServer idle = f.registry.forProject(f.project("idle"));
        long ga = a.generation();
        long gb = b.generation();
        long gi = idle.generation();

        f.registry.stopAll(StopReason.SETTINGS_CHANGED);

        assertFalse(f.registry.isShuttingDown());
        assertEquals(1, f.scheduler.snapshot().size());
        assertTrue(f.launched(0).isAlive() && f.launched(1).isAlive()); // not on the caller's thread
        f.scheduler.runPending();
        assertEquals(List.of("destroy"), f.launched(0).calls);
        assertEquals(List.of("destroy"), f.launched(1).calls);
        assertEquals(ga + 1, a.generation());
        assertEquals(gb + 1, b.generation());
        assertEquals(gi, idle.generation());
        assertFalse(a.isStoppedIntentionally());
        assertEquals(6010, a.ensureReady(EXE));
    }

    @Test
    void projectClosedIsNotARegistryWideStop() throws IOException {
        HeadlessServer a = started("a");
        long generation = a.generation();

        f.registry.stopAll(StopReason.PROJECT_CLOSED);

        assertTrue(f.launched(0).isAlive());
        assertTrue(f.scheduler.snapshot().isEmpty());
        assertEquals(generation, a.generation());
        assertEquals(State.READY, a.state());
    }

    @Test
    void versionIsDetectedOncePerExecutable() throws IOException {
        started("a");
        started("b");
        assertEquals(1, f.versionChecks.get());
    }

    @Test
    void shutdownHookIsInstalledOnceAndOnlySignalsTheProcessTrees() throws IOException {
        started("a");
        HeadlessServer b = started("b");
        b.close(); // detached, kill pending
        FakeGodotProcess stubborn = f.launched(0);
        stubborn.diesOnDestroy = false;

        assertEquals(1, f.hooks.size());
        f.hooks.get(0).run();

        assertEquals(List.of("destroy"), stubborn.calls); // no wait, so no forced kill either
        assertEquals(List.of("destroy"), f.launched(1).calls);
    }
}
