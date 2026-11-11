package io.ziumper.godot.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

class GodotActionProviderTest {

    private FileObject dir;
    private int counter;

    @BeforeEach
    void setUp() throws IOException {
        dir = FileUtil.createMemoryFileSystem().getRoot().createFolder("game");
    }

    private boolean isMainLoop(String script) throws IOException {
        FileObject fo = TestFiles.write(dir, "script" + (counter++) + ".gd", script);
        return GodotActionProvider.isMainLoopScript(fo);
    }

    @Test
    void recognizesSceneTreeAndMainLoop() throws IOException {
        assertTrue(isMainLoop("extends SceneTree\n\nfunc _init():\n\tquit()\n"));
        assertTrue(isMainLoop("extends MainLoop\n"));
    }

    @Test
    void rejectsOrdinaryNodeScripts() throws IOException {
        assertFalse(isMainLoop("extends Node\n"));
        assertFalse(isMainLoop("extends Node2D\n"));
        assertFalse(isMainLoop("extends SceneTreeTimer\n"));
    }

    @Test
    void skipsCommentsBlankLinesAndAnnotations() throws IOException {
        assertTrue(isMainLoop("# Tool script\n\n@tool\n@icon(\"res://icon.svg\")\nextends SceneTree # entry point\n"));
    }

    @Test
    void acceptsClassNameBeforeExtends() throws IOException {
        assertTrue(isMainLoop("class_name Runner\nextends SceneTree\n"));
        assertTrue(isMainLoop("class_name Runner extends SceneTree\n"));
    }

    @Test
    void rejectsPathExtends() throws IOException {
        assertFalse(isMainLoop("extends \"res://base.gd\"\n"));
    }

    @Test
    void rejectsWhenExtendsIsNotTheFirstStatement() throws IOException {
        assertFalse(isMainLoop("var x = 1\nextends SceneTree\n"));
    }

    @Test
    void rejectsEmptyScript() throws IOException {
        assertFalse(isMainLoop(""));
        assertFalse(isMainLoop("# only a comment\n"));
    }
}
