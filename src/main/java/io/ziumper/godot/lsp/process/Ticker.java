package io.ziumper.godot.lsp.process;

/** Source of time and of waiting, so tests can run the start and stop timeouts without real waiting. */
public interface Ticker {

    /** Monotonic time in nanoseconds, as {@link System#nanoTime()}. */
    long nanos();

    void sleep(long ms) throws InterruptedException;

    Ticker SYSTEM = new Ticker() {
        @Override
        public long nanos() {
            return System.nanoTime();
        }

        @Override
        public void sleep(long ms) throws InterruptedException {
            Thread.sleep(ms);
        }
    };
}
