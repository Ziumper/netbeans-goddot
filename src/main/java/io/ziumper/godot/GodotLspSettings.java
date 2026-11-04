package io.ziumper.godot;

import java.util.prefs.Preferences;
import org.openide.util.NbPreferences;

/**
 * Settings of the connection to the Godot Editor LSP server.
 * Stored in NbPreferences (no Options panel, see report).
 */
public final class GodotLspSettings {

    public static final String PROP_HOST = "host";
    public static final String PROP_PORT = "port";
    public static final String PROP_CONNECT_TIMEOUT = "connectTimeoutMs";
    public static final String PROP_AUTO_RECONNECT = "autoReconnect";
    public static final String PROP_RETRY_INTERVAL = "retryIntervalMs";

    public static final String PROP_GODOT_EXECUTABLE = "godotExecutable";

    public static final String DEFAULT_GODOT_EXECUTABLE = "godot";
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 6005;
    public static final int DEFAULT_CONNECT_TIMEOUT = 1000;
    public static final boolean DEFAULT_AUTO_RECONNECT = true;
    public static final int DEFAULT_RETRY_INTERVAL = 3000;

    private GodotLspSettings() {
    }

    private static Preferences prefs() {
        return NbPreferences.forModule(GodotLspSettings.class);
    }

    public static String getHost() {
        String h = prefs().get(PROP_HOST, DEFAULT_HOST).trim();
        return h.isEmpty() ? DEFAULT_HOST : h;
    }

    public static void setHost(String host) {
        prefs().put(PROP_HOST, host);
    }

    public static int getPort() {
        int p = prefs().getInt(PROP_PORT, DEFAULT_PORT);
        return p > 0 && p < 65536 ? p : DEFAULT_PORT;
    }

    public static void setPort(int port) {
        prefs().putInt(PROP_PORT, port);
    }

    public static int getConnectTimeoutMs() {
        return Math.max(100, prefs().getInt(PROP_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT));
    }

    public static void setConnectTimeoutMs(int ms) {
        prefs().putInt(PROP_CONNECT_TIMEOUT, ms);
    }

    public static boolean isAutoReconnect() {
        return prefs().getBoolean(PROP_AUTO_RECONNECT, DEFAULT_AUTO_RECONNECT);
    }

    public static void setAutoReconnect(boolean value) {
        prefs().putBoolean(PROP_AUTO_RECONNECT, value);
    }

    public static int getRetryIntervalMs() {
        return Math.max(500, prefs().getInt(PROP_RETRY_INTERVAL, DEFAULT_RETRY_INTERVAL));
    }

    public static void setRetryIntervalMs(int ms) {
        prefs().putInt(PROP_RETRY_INTERVAL, ms);
    }

    /** Path to the Godot executable used by Run actions; defaults to "godot" from PATH. */
    public static String getGodotExecutable() {
        String e = prefs().get(PROP_GODOT_EXECUTABLE, DEFAULT_GODOT_EXECUTABLE).trim();
        return e.isEmpty() ? DEFAULT_GODOT_EXECUTABLE : e;
    }

    public static void setGodotExecutable(String path) {
        prefs().put(PROP_GODOT_EXECUTABLE, path);
    }
}
