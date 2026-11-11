package io.ziumper.godot.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GodotExecutablesTest {

    @TempDir
    Path tmp;

    @Test
    void acceptsExistingExecutableFileByPath() throws IOException {
        File exe = Files.createFile(tmp.resolve("godot-test-exe")).toFile();
        exe.setExecutable(true);
        assertTrue(GodotExecutables.isExecutable(exe.getAbsolutePath()));
    }

    @Test
    void rejectsMissingFileByPath() {
        assertFalse(GodotExecutables.isExecutable(tmp.resolve("missing").toString()));
    }

    @Test
    void rejectsDirectory() {
        assertFalse(GodotExecutables.isExecutable(tmp.toString()));
    }

    @Test
    void rejectsBareNameNotOnPath() {
        assertFalse(GodotExecutables.isExecutable("definitely-not-a-godot-binary-7f3a"));
    }
}
