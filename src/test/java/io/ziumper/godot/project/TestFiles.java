package io.ziumper.godot.project;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.openide.filesystems.FileObject;

/** Helpers for creating files in an in-memory NetBeans file system. */
final class TestFiles {

    private TestFiles() {
    }

    /** Creates (or overwrites) {@code name} in {@code dir} with UTF-8 {@code content}. */
    static FileObject write(FileObject dir, String name, String content) throws IOException {
        FileObject fo = dir.getFileObject(name);
        if (fo == null) {
            fo = dir.createData(name);
        }
        try (OutputStream os = fo.getOutputStream()) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return fo;
    }
}
