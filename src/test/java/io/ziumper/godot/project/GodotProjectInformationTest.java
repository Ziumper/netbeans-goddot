package io.ziumper.godot.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

class GodotProjectInformationTest {

    private FileObject dir;

    @BeforeEach
    void setUp() throws IOException {
        dir = FileUtil.createMemoryFileSystem().getRoot().createFolder("game");
    }

    private String readName(String projectGodot) throws IOException {
        TestFiles.write(dir, "project.godot", projectGodot);
        return GodotProjectInformation.readConfigName(dir);
    }

    @Test
    void readsNameFromApplicationSection() throws IOException {
        String cfg = """
                ; Engine configuration file.
                config_version=5

                [application]

                config/name="My Game"
                run/main_scene="res://main.tscn"
                config/features=PackedStringArray("4.3", "Forward Plus")
                """;
        assertEquals("My Game", readName(cfg));
    }

    @Test
    void returnsNullWithoutProjectFile() {
        assertNull(GodotProjectInformation.readConfigName(dir));
    }

    @Test
    void ignoresNameOutsideApplicationSection() throws IOException {
        String cfg = """
                [rendering]
                config/name="Wrong"

                [application]
                run/main_scene="res://main.tscn"
                """;
        assertNull(readName(cfg));
    }

    @Test
    void unescapesQuotesAndBackslashes() throws IOException {
        assertEquals("Say \"hi\"", readName("[application]\nconfig/name=\"Say \\\"hi\\\"\"\n"));
        assertEquals("a\\b", readName("[application]\nconfig/name=\"a\\\\b\"\n"));
    }

    @Test
    void treatsBlankNameAsMissing() throws IOException {
        assertNull(readName("[application]\nconfig/name=\"\"\n"));
        assertNull(readName("[application]\nconfig/name=\"   \"\n"));
    }

    @Test
    void handlesWindowsLineEndings() throws IOException {
        assertEquals("Win Game", readName("[application]\r\nconfig/name=\"Win Game\"\r\n"));
    }
}
