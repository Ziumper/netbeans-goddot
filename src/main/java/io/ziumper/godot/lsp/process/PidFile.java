package io.ziumper.godot.lsp.process;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/** Record of a headless server we started, kept next to its log so a later IDE session can find leftovers. */
public record PidFile(long pid, int port, String projectDir, long startedAt) {

    public static Path pathFor(Path tmpDir, String name, int port) {
        return tmpDir.resolve("godot-lsp-" + name + "-" + port + ".pid");
    }

    public void write(Path path) throws IOException {
        Properties p = new Properties();
        p.setProperty("pid", Long.toString(pid));
        p.setProperty("port", Integer.toString(port));
        p.setProperty("projectDir", projectDir);
        p.setProperty("startedAt", Long.toString(startedAt));
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            p.store(w, null);
        }
    }

    /** Empty if the file is missing, unreadable or incomplete; never throws. */
    public static Optional<PidFile> read(Path path) {
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Properties p = new Properties();
            p.load(r);
            String dir = p.getProperty("projectDir");
            long pid = Long.parseLong(p.getProperty("pid").trim());
            int port = Integer.parseInt(p.getProperty("port").trim());
            long started = Long.parseLong(p.getProperty("startedAt").trim());
            if (dir == null || dir.isEmpty() || pid <= 0 || port <= 0) {
                return Optional.empty();
            }
            return Optional.of(new PidFile(pid, port, dir, started));
        } catch (Exception ex) { // missing file, I/O error, garbage, missing keys
            return Optional.empty();
        }
    }

    public boolean matchesProject(File dir) {
        return canonical(new File(projectDir)).equals(canonical(dir));
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException ex) {
            return f.getAbsolutePath();
        }
    }
}
