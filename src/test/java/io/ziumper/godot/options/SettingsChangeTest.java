package io.ziumper.godot.options;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.ziumper.godot.options.SettingsChange.Decision;
import io.ziumper.godot.options.SettingsChange.Values;
import org.junit.jupiter.api.Test;

class SettingsChangeTest {

    private static final Values BASE = new Values(true, "/usr/bin/godot", "127.0.0.1", 6005, 1000);

    @Test
    void nothingChanged() {
        assertEquals(new Decision(false, false), SettingsChange.decide(BASE, BASE));
    }

    @Test
    void headlessTurnedOffStopsAndReconnects() {
        Values after = new Values(false, BASE.executable(), BASE.host(), BASE.port(), BASE.timeout());
        assertEquals(new Decision(true, true), SettingsChange.decide(BASE, after));
    }

    @Test
    void headlessTurnedOnOnlyReconnects() {
        Values off = new Values(false, BASE.executable(), BASE.host(), BASE.port(), BASE.timeout());
        assertEquals(new Decision(false, true), SettingsChange.decide(off, BASE));
    }

    @Test
    void executableChangedWhileHeadlessStopsAndReconnects() {
        Values after = new Values(true, "/opt/godot4", BASE.host(), BASE.port(), BASE.timeout());
        assertEquals(new Decision(true, true), SettingsChange.decide(BASE, after));
    }

    @Test
    void executableChangedWithoutHeadlessDoesNothing() {
        Values off = new Values(false, "/usr/bin/godot", "127.0.0.1", 6005, 1000);
        Values after = new Values(false, "/opt/godot4", "127.0.0.1", 6005, 1000);
        assertEquals(new Decision(false, false), SettingsChange.decide(off, after));
    }

    @Test
    void hostPortOrTimeoutChangedOnlyReconnects() {
        assertEquals(new Decision(false, true),
                SettingsChange.decide(BASE, new Values(true, BASE.executable(), "10.0.0.1", 6005, 1000)));
        assertEquals(new Decision(false, true),
                SettingsChange.decide(BASE, new Values(true, BASE.executable(), "127.0.0.1", 6006, 1000)));
        assertEquals(new Decision(false, true),
                SettingsChange.decide(BASE, new Values(true, BASE.executable(), "127.0.0.1", 6005, 2000)));
    }
}
