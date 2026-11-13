package io.ziumper.godot.lsp.process;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FirstResponseWatchdogTest {

    private final FakeScheduler scheduler = new FakeScheduler();
    private final FirstResponseWatchdog watchdog = new FirstResponseWatchdog(scheduler);
    private final AtomicInteger fired = new AtomicInteger();

    @Test
    void firesWithoutAByte() {
        watchdog.arm(30_000, fired::incrementAndGet);
        scheduler.runPending();
        assertEquals(1, fired.get());
    }

    @Test
    void doesNotFireAfterFirstByte() {
        watchdog.arm(30_000, fired::incrementAndGet);
        watchdog.onFirstByte();
        scheduler.runPending();
        assertEquals(0, fired.get());
    }

    @Test
    void doesNotFireAfterDisarm() {
        watchdog.arm(30_000, fired::incrementAndGet);
        watchdog.disarm();
        scheduler.runPending();
        assertEquals(0, fired.get());
    }

    @Test
    void doubleFirstByteIsSafe() {
        watchdog.arm(30_000, fired::incrementAndGet);
        watchdog.onFirstByte();
        watchdog.onFirstByte();
        watchdog.disarm();
        scheduler.runPending();
        assertEquals(0, fired.get());
    }

    @Test
    void racingTimeoutAfterFirstByteIsIgnored() {
        watchdog.arm(30_000, fired::incrementAndGet);
        java.util.List<Runnable> raced = scheduler.snapshot();
        watchdog.onFirstByte();
        scheduler.runSnapshot(raced);
        assertEquals(0, fired.get());
    }

    @Test
    void firesOnlyOnce() {
        watchdog.arm(30_000, fired::incrementAndGet);
        java.util.List<Runnable> twice = scheduler.snapshot();
        scheduler.runSnapshot(twice);
        scheduler.runSnapshot(twice);
        assertEquals(1, fired.get());
    }

    @Test
    void rearmReplacesThePreviousTimeout() {
        AtomicInteger first = new AtomicInteger();
        watchdog.arm(30_000, first::incrementAndGet);
        watchdog.arm(30_000, fired::incrementAndGet);
        scheduler.runPending();
        assertEquals(0, first.get());
        assertEquals(1, fired.get());
    }
}
