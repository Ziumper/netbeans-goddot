package io.ziumper.godot.lsp.process;

import java.io.IOException;

/** The start was refused on purpose (IDE exit, closed project); not a failure to report or retry. */
public final class StartRefusedException extends IOException {

    StartRefusedException(String message) {
        super(message);
    }
}
