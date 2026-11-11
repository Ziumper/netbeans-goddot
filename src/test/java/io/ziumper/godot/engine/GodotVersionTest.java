package io.ziumper.godot.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class GodotVersionTest {

    @Test
    void parsesOfficialStableBuild() {
        assertEquals(Optional.of("4.6"), GodotVersion.parse("4.6.3.stable.official.7d41c59c4"));
    }

    @Test
    void parsesVersionWithoutPatch() {
        assertEquals(Optional.of("4.2"), GodotVersion.parse("4.2.stable.official"));
    }

    @Test
    void parsesMonoBuild() {
        assertEquals(Optional.of("3.5"), GodotVersion.parse("3.5.2.stable.mono.official.04ee8c7a2"));
    }

    @Test
    void parsesPreReleaseBuild() {
        assertEquals(Optional.of("4.4"), GodotVersion.parse("4.4.beta1.official"));
    }

    @Test
    void skipsNoiseLinesBeforeTheVersion() {
        String output = "Godot Engine v4.3.stable - https://godotengine.org\r\n4.3.stable.official.77dcf97d8\r\n";
        assertEquals(Optional.of("4.3"), GodotVersion.parse(output));
    }

    @Test
    void returnsEmptyForUnrecognizedOutput() {
        assertEquals(Optional.empty(), GodotVersion.parse(null));
        assertEquals(Optional.empty(), GodotVersion.parse(""));
        assertEquals(Optional.empty(), GodotVersion.parse("command not found"));
        assertEquals(Optional.empty(), GodotVersion.parse("4"));
    }

    @Test
    void detectReturnsEmptyForMissingExecutable() {
        assertEquals(Optional.empty(), GodotVersion.detect((String) null));
        assertEquals(Optional.empty(), GodotVersion.detect("definitely-not-a-godot-binary-7f3a"));
    }
}
