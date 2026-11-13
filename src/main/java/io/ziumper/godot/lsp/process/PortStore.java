package io.ziumper.godot.lsp.process;

import java.io.File;
import java.util.Set;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

/** Remembers the port assigned to each project, across IDE sessions. */
public interface PortStore {

    /** The port stored for the project, or -1 if there is none. */
    int load(File projectDir);

    void save(File projectDir, int port);

    /** Ports stored for all the other projects. */
    Set<Integer> takenByOthers(File projectDir);

    /** Keys {@code port.<sha1 of the path>} in the given preferences node, read on every call. */
    static PortStore preferences(Supplier<Preferences> node) {
        return new PreferencesPortStore(node);
    }
}
