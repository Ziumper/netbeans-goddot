package io.ziumper.godot.lsp.process;

/** Why the headless servers are stopped. */
public enum StopReason {
    PROJECT_CLOSED,
    IDE_EXIT,
    SETTINGS_CHANGED
}
