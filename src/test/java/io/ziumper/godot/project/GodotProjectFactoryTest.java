package io.ziumper.godot.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openide.filesystems.FileObject;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectInformation;
import org.netbeans.spi.project.ProjectState;
import org.openide.filesystems.FileUtil;

class GodotProjectFactoryTest {

    private final GodotProjectFactory factory = new GodotProjectFactory();
    private FileObject root;

    @BeforeEach
    void setUp() throws IOException {
        root = FileUtil.createMemoryFileSystem().getRoot();
    }

    @Test
    void directoryWithProjectGodotIsAProject() throws IOException {
        FileObject dir = root.createFolder("game");
        TestFiles.write(dir, "project.godot", "[application]\n");
        assertTrue(factory.isProject(dir));
    }

    @Test
    void emptyDirectoryIsNotAProject() throws IOException {
        assertFalse(factory.isProject(root.createFolder("empty")));
    }

    @Test
    void directoryWithoutProjectGodotIsNotAProject() throws IOException {
        FileObject dir = root.createFolder("other");
        TestFiles.write(dir, "main.gd", "extends Node\n");
        assertFalse(factory.isProject(dir));
    }

    @Test
    void loadProjectReturnsNullForNonGodotDirectory() throws IOException {
        // ProjectManager.findProject asks every factory to load a folder without calling isProject
        // first; claiming it would steal e.g. a Maven project's src/main/java.
        FileObject dir = root.createFolder("java");
        TestFiles.write(dir, "Main.java", "class Main {}\n");
        assertNull(factory.loadProject(dir, null));
    }

    @Test
    void loadProjectReturnsProjectForGodotDirectory() throws IOException {
        FileObject dir = root.createFolder("game2");
        TestFiles.write(dir, "project.godot", "[application]\n");
        ProjectState state = new ProjectState() {
            @Override
            public void markModified() {
            }

            @Override
            public void notifyDeleted() {
            }
        };
        Project p = factory.loadProject(dir, state);
        assertNotNull(p);
        assertEquals(dir, p.getProjectDirectory());
        assertNotNull(p.getLookup().lookup(ProjectInformation.class));
    }
}
