package io.ziumper.godot.lsp.process;

import io.ziumper.godot.engine.GodotVersion;
import java.util.Optional;

/** Detects the Godot version of an executable. */
@FunctionalInterface
public interface VersionDetector {

    /** {@code major.minor} of the executable, empty if it cannot be detected. Blocking. */
    Optional<String> detect(String exe);

    /** Runs {@code <exe> --version}. */
    VersionDetector GODOT = GodotVersion::detect;
}
