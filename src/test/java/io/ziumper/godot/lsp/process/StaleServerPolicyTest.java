package io.ziumper.godot.lsp.process;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Optional;
import java.util.function.LongFunction;
import org.junit.jupiter.api.Test;

class StaleServerPolicyTest {

    private static final File DIR = new File("/tmp/game-project");
    private static final PidFile FILE = new PidFile(42, 6010, DIR.getAbsolutePath(), 0);

    private static LongFunction<Optional<GodotProcess>> finds(GodotProcess p) {
        return pid -> pid == p.pid() ? Optional.of(p) : Optional.empty();
    }

    private static StaleServerPolicy.Decision decide(Optional<PidFile> f, File dir, int port, GodotProcess p) {
        return StaleServerPolicy.decide(f, dir, port, finds(p));
    }

    @Test
    void noPidFileLeavesAlone() {
        assertFalse(decide(Optional.empty(), DIR, 6010, new FakeGodotProcess(42, "godot")).kill());
    }

    @Test
    void otherProjectLeavesAlone() {
        assertFalse(decide(Optional.of(FILE), new File("/tmp/other"), 6010, new FakeGodotProcess(42, "godot")).kill());
    }

    @Test
    void otherPortLeavesAlone() {
        assertFalse(decide(Optional.of(FILE), DIR, 6011, new FakeGodotProcess(42, "godot")).kill());
    }

    @Test
    void deadOrUnknownProcessLeavesAlone() {
        FakeGodotProcess dead = new FakeGodotProcess(42, "godot");
        dead.alive = false;
        assertFalse(decide(Optional.of(FILE), DIR, 6010, dead).kill());
        assertFalse(StaleServerPolicy.decide(Optional.of(FILE), DIR, 6010, pid -> Optional.empty()).kill());
    }

    @Test
    void foreignCommandLeavesAlone() {
        assertFalse(decide(Optional.of(FILE), DIR, 6010, new FakeGodotProcess(42, "/usr/bin/java")).kill());
    }

    @Test
    void unavailableCommandTrustsThePidFile() {
        FakeGodotProcess p = new FakeGodotProcess(42, null);
        StaleServerPolicy.Decision d = decide(Optional.of(FILE), DIR, 6010, p);
        assertTrue(d.kill());
        assertSame(p, d.process());
    }

    @Test
    void fullMatchKills() {
        FakeGodotProcess p = new FakeGodotProcess(42, "/opt/Godot_v4.4/GODOT.x86_64");
        StaleServerPolicy.Decision d = decide(Optional.of(FILE), DIR, 6010, p);
        assertTrue(d.kill());
        assertSame(p, d.process());
    }
}
