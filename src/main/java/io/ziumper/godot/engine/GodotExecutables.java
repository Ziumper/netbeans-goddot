package io.ziumper.godot.engine;

import java.io.File;

/** Shared helpers for locating the Godot executable. */
public final class GodotExecutables {

    private GodotExecutables() {
    }

    /** True if {@code exe} is an executable file, either as a path or found on PATH. */
    public static boolean isExecutable(String exe) {
        File f = new File(exe);
        if (f.getParent() != null || exe.contains(File.separator)) {
            return f.isFile() && f.canExecute();
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            File c = new File(dir, exe);
            if (c.isFile() && c.canExecute()) {
                return true;
            }
        }
        return false;
    }
}
