package io.ziumper.godot.lsp.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PidFileTest {

    @TempDir
    Path tmp;

    @Test
    void pathIsNextToTheLog() {
        assertEquals(tmp.resolve("godot-lsp-game-6010.pid"), PidFile.pathFor(tmp, "game", 6010));
    }

    @Test
    void roundTrip() throws IOException {
        Path f = PidFile.pathFor(tmp, "game", 6010);
        PidFile written = new PidFile(1234, 6010, tmp.resolve("game").toAbsolutePath().toString(), 99L);
        written.write(f);
        assertEquals(Optional.of(written), PidFile.read(f));
    }

    @Test
    void missingFileIsEmpty() {
        assertTrue(PidFile.read(tmp.resolve("nope.pid")).isEmpty());
    }

    @Test
    void garbageIsEmpty() throws IOException {
        Path f = tmp.resolve("x.pid");
        Files.writeString(f, "\u0000\u0001 not a pid file \\u00zz");
        assertTrue(PidFile.read(f).isEmpty());
    }

    @Test
    void incompleteIsEmpty() throws IOException {
        Path f = tmp.resolve("x.pid");
        Files.writeString(f, "pid=12\nport=6010\n");
        assertTrue(PidFile.read(f).isEmpty());
        Files.writeString(f, "pid=abc\nport=6010\nprojectDir=/x\nstartedAt=1\n");
        assertTrue(PidFile.read(f).isEmpty());
    }

    @Test
    void matchesProjectBySamePathOnly() throws IOException {
        File dir = Files.createDirectory(tmp.resolve("a")).toFile();
        File other = Files.createDirectory(tmp.resolve("b")).toFile();
        PidFile f = new PidFile(1, 6010, dir.getAbsolutePath(), 0);
        assertTrue(f.matchesProject(dir));
        assertTrue(f.matchesProject(new File(dir, "../a")));
        assertFalse(f.matchesProject(other));
    }
}
