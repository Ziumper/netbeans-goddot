package io.ziumper.godot.engine;

import io.ziumper.godot.options.GodotSettings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects the version of the configured Godot executable by running {@code <exe> --version}. */
public final class GodotVersion {

    private static final long TIMEOUT_SECONDS = 4;
    /** Matches a line like {@code 4.6.3.stable.official.7d41c59c4}; captures major.minor. */
    private static final Pattern VERSION_LINE = Pattern.compile("^\\s*(\\d+\\.\\d+)(?:\\.\\d+)*(?:[.\\-+].*)?$");

    /** Minimum Godot version for the headless LSP: {@code --lsp-port} first appeared in Godot 4.2. */
    public static final int HEADLESS_LSP_MIN_MAJOR = 4;
    public static final int HEADLESS_LSP_MIN_MINOR = 2;

    private GodotVersion() {
    }

    /** The minimum headless-LSP version as {@code major.minor}, for messages. */
    public static String headlessLspMinVersion() {
        return HEADLESS_LSP_MIN_MAJOR + "." + HEADLESS_LSP_MIN_MINOR;
    }

    /** True when {@code version} is new enough for the headless LSP. Unparseable input yields false. */
    public static boolean supportsHeadlessLsp(String version) {
        return isAtLeast(version, HEADLESS_LSP_MIN_MAJOR, HEADLESS_LSP_MIN_MINOR);
    }

    /** Version of the configured executable as {@code major.minor}, or empty on any failure. Blocking; not for the EDT. */
    public static Optional<String> detect() {
        return detect(GodotSettings.getGodotExecutable());
    }

    public static Optional<String> detect(String exe) {
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

    /**
     * True when {@code version} (as returned by {@link #detect}, or a longer string such as
     * {@code 4.3.stable.official.77dcf97d8}) is at least {@code major.minor}. Unparseable input yields false.
     */
    public static boolean isAtLeast(String version, int major, int minor) {
        if (version == null) {
            return false;
        }
        Matcher m = Pattern.compile("^\\s*(\\d+)\\.(\\d+)").matcher(version);
        if (!m.find()) {
            return false;
        }
        try {
            int ma = Integer.parseInt(m.group(1));
            int mi = Integer.parseInt(m.group(2));
            return ma > major || (ma == major && mi >= minor);
        } catch (NumberFormatException ex) {
            return false;
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
