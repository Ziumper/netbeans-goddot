package io.ziumper.godot.lsp.process;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** A registry wired to fakes: no processes, no sockets, no real waiting. */
final class ServerFixture {

    static final String EXE = "/opt/godot/godot";

    final FakePorts ports = new FakePorts();
    final FakeLauncher launcher = new FakeLauncher();
    final MemoryPortStore store = new MemoryPortStore();
    final FakeTicker ticker = new FakeTicker();
    final FakeScheduler scheduler = new FakeScheduler();
    /** Processes the registry finds by PID (leftovers named in PID files). */
    final Map<Long, GodotProcess> running = new ConcurrentHashMap<>();
    final List<Thread> hooks = new CopyOnWriteArrayList<>();
    final AtomicInteger versionChecks = new AtomicInteger();
    volatile String version = "4.3";
    final Path tmp;
    final ServerRegistry registry;

    ServerFixture(Path tmp) {
        this.tmp = tmp;
        launcher.onLaunch = l -> ports.listen(l.port(), l.process());
        registry = new ServerRegistry(launcher, ports, store,
                exe -> {
                    versionChecks.incrementAndGet();
                    return Optional.ofNullable(version);
                },
                pid -> Optional.ofNullable(running.get(pid)), scheduler, ticker, tmp, hooks::add,
                ServerRegistry.Limits.DEFAULT);
    }

    File project(String name) throws IOException {
        return Files.createDirectories(tmp.resolve(name)).toFile();
    }

    FakeGodotProcess launched(int i) {
        return launcher.launches.get(i).process();
    }

    /** Launches fake processes; by default each one listens on its port at once. */
    static final class FakeLauncher implements ProcessLauncher {

        record Launch(String exe, File dir, int port, File log, FakeGodotProcess process) {
        }

        final List<Launch> launches = new CopyOnWriteArrayList<>();
        volatile boolean executable = true;
        /** Runs right after each launch, on the starting thread. */
        volatile Consumer<Launch> onLaunch = l -> {
        };
        private final AtomicLong nextPid = new AtomicLong(1000);

        @Override
        public boolean isExecutable(String exe) {
            return executable;
        }

        @Override
        public GodotProcess launch(String exe, File projectDir, int port, File log) {
            FakeGodotProcess p = new FakeGodotProcess(nextPid.getAndIncrement(), exe);
            Launch l = new Launch(exe, projectDir, port, log, p);
            launches.add(l);
            onLaunch.accept(l);
            return p;
        }
    }

    /** A port is open while the process listening on it is alive, or for good if a foreign program holds it. */
    static final class FakePorts implements PortProbe {

        private final Map<Integer, GodotProcess> listeners = new HashMap<>();
        private final Set<Integer> foreign = new HashSet<>();

        synchronized void listen(int port, GodotProcess p) {
            listeners.put(port, p);
        }

        synchronized void takeForeign(int port) {
            foreign.add(port);
        }

        @Override
        public synchronized boolean isOpen(int port) {
            GodotProcess p = listeners.get(port);
            return foreign.contains(port) || (p != null && p.isAlive());
        }

        @Override
        public boolean isFree(int port) {
            return !isOpen(port);
        }
    }

    static final class MemoryPortStore implements PortStore {

        final Map<String, Integer> ports = new HashMap<>();

        @Override
        public synchronized int load(File projectDir) {
            return ports.getOrDefault(projectDir.getAbsolutePath(), -1);
        }

        @Override
        public synchronized void save(File projectDir, int port) {
            ports.put(projectDir.getAbsolutePath(), port);
        }

        @Override
        public synchronized Set<Integer> takenByOthers(File projectDir) {
            Set<Integer> taken = new HashSet<>();
            ports.forEach((dir, port) -> {
                if (!dir.equals(projectDir.getAbsolutePath())) {
                    taken.add(port);
                }
            });
            return taken;
        }
    }

    /** Time moves only when somebody sleeps or the test advances it. */
    static final class FakeTicker implements Ticker {

        private final AtomicLong now = new AtomicLong();
        private final AtomicInteger sleeps = new AtomicInteger();
        /** Runs at every sleep, on the sleeping thread: things that happen while a start waits. */
        volatile Runnable onSleep = () -> {
        };

        @Override
        public long nanos() {
            return now.get();
        }

        @Override
        public void sleep(long ms) {
            now.addAndGet(ms * 1_000_000);
            sleeps.incrementAndGet();
            onSleep.run();
        }

        void advance(long ms) {
            now.addAndGet(ms * 1_000_000);
        }

        int sleeps() {
            return sleeps.get();
        }

        /** Runs the action at the first sleep only. */
        void onFirstSleep(Runnable action) {
            AtomicInteger calls = new AtomicInteger();
            onSleep = () -> {
                if (calls.getAndIncrement() == 0) {
                    action.run();
                }
            };
        }
    }
}
