package io.ziumper.godot.lsp.process;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ProcessTreesTest {

    private static final long LIMIT = TimeUnit.MILLISECONDS.toNanos(20);

    @Test
    void gentleStopIsEnough() {
        FakeGodotProcess p = new FakeGodotProcess(1, "godot");
        ProcessTrees.terminateAll(List.of(p), LIMIT);
        assertEquals(List.of("destroy"), p.calls);
    }

    @Test
    void stubbornProcessIsKilledForciblyAfterTheLimit() {
        FakeGodotProcess p = new FakeGodotProcess(1, "godot");
        p.diesOnDestroy = false;
        ProcessTrees.terminateAll(List.of(p), LIMIT);
        assertEquals(List.of("destroy", "destroyForcibly"), p.calls);
    }

    @Test
    void onlySurvivorsAreKilledForcibly() {
        FakeGodotProcess good = new FakeGodotProcess(1, "godot");
        FakeGodotProcess bad = new FakeGodotProcess(2, "godot");
        bad.diesOnDestroy = false;
        ProcessTrees.terminateAll(List.of(good, bad), LIMIT);
        assertEquals(List.of("destroy"), good.calls);
        assertEquals(List.of("destroy", "destroyForcibly"), bad.calls);
    }
}
