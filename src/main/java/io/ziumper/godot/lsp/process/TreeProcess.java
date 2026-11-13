package io.ziumper.godot.lsp.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Shared tree handling over a {@link ProcessHandle}. The descendants are collected before the parent is
 * signalled: once it is gone they are reparented and can no longer be found through it.
 */
abstract class TreeProcess implements GodotProcess {

    private final ProcessHandle root;
    /** Handles of the tree as of the last destroy; null before. */
    private volatile List<ProcessHandle> tree;

    TreeProcess(ProcessHandle root) {
        this.root = root;
    }

    @Override
    public long pid() {
        return root.pid();
    }

    @Override
    public boolean isAlive() {
        List<ProcessHandle> t = tree;
        if (t == null) {
            return root.isAlive();
        }
        return t.stream().anyMatch(ProcessHandle::isAlive);
    }

    @Override
    public void destroy() {
        List<ProcessHandle> handles = collect();
        tree = handles;
        handles.forEach(ProcessHandle::destroy);
    }

    @Override
    public void destroyForcibly() {
        List<ProcessHandle> handles = collect();
        List<ProcessHandle> old = tree;
        if (old != null) {
            old.stream().filter(h -> !handles.contains(h)).forEach(handles::add);
        }
        tree = handles;
        handles.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    @Override
    public CompletableFuture<?> onExit() {
        List<ProcessHandle> t = tree;
        if (t == null) {
            return root.onExit();
        }
        return CompletableFuture.allOf(t.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new));
    }

    @Override
    public Optional<String> command() {
        return root.info().command();
    }

    /** Descendants first, the root last. */
    private List<ProcessHandle> collect() {
        List<ProcessHandle> handles = new ArrayList<>();
        root.descendants().forEach(handles::add);
        handles.add(root);
        return handles;
    }
}
