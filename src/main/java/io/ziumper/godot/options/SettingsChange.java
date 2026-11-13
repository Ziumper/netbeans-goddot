package io.ziumper.godot.options;

import java.util.Objects;

/** Decides what to do after the Godot settings were applied. */
public final class SettingsChange {

    /** The settings that matter for the connection. */
    public record Values(boolean headless, String executable, String host, int port, int timeout) {
    }

    /** What to do: stop the headless servers and/or reconnect the clients. */
    public record Decision(boolean stopServers, boolean reconnect) {
    }

    private SettingsChange() {
    }

    public static Decision decide(Values before, Values after) {
        boolean stop = before.headless() && !after.headless()
                || before.headless() && after.headless()
                        && !Objects.equals(before.executable(), after.executable());
        boolean reconnect = stop
                || !Objects.equals(before.host(), after.host())
                || before.port() != after.port()
                || before.timeout() != after.timeout()
                || before.headless() != after.headless();
        return new Decision(stop, reconnect);
    }
}
