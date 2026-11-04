package io.ziumper.godot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects the version of the configured Godot executable by running {@code <exe> --version}. */
final class GodotVersion {

    private static final long TIMEOUT_SECONDS = 4;
    /** Matches a line like {@code 4.6.3.stable.official.7d41c59c4}; captures major.minor. */
    private static final Pattern VERSION_LINE = Pattern.compile("^\\s*(\\d+\\.\\d+)(?:\\.\\d+)*(?:[.\\-+].*)?$");

    private GodotVersion() {
    }

    /** Version of the configured executable as {@code major.minor}, or empty on any failure. Blocking; not for the EDT. */
    static Optional<String> detect() {
        return detect(GodotLspSettings.getGodotExecutable());
    }

    static Optional<String> detect(String exe) {
        if (exe == null || !GodotExecutables.isExecutable(exe)) {
            return Optional.empty();
        }
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(exe, "--version");
            pb.redirectErrorStream(true);
            p = pb.start();
            p.getOutputStream().close();
            final InputStream in = p.getInputStream();
            final StringBuilder out = new StringBuilder();
            // Drain on a separate thread so a hung process cannot block us past the timeout.
            Thread reader = new Thread(() -> {
                byte[] buf = new byte[4096];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        synchronized (out) {
                            if (out.length() < 65536) {
                                out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                            }
                        }
                    }
                } catch (IOException ignore) {
                    // process was killed or stream closed
                }
            }, "godot --version reader");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return Optional.empty();
            }
            reader.join(1000);
            String text;
            synchronized (out) {
                text = out.toString();
            }
            return parse(text);
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    /** Extracts {@code major.minor} from the first output line that looks like a version. */
    static Optional<String> parse(String output) {
        if (output == null) {
            return Optional.empty();
        }
        for (String line : output.split("\\R")) {
            Matcher m = VERSION_LINE.matcher(line);
            if (m.matches()) {
                return Optional.of(m.group(1));
            }
        }
        return Optional.empty();
    }
}
