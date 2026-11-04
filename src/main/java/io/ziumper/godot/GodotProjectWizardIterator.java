package io.ziumper.godot;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.LinkedHashSet;
import javax.swing.event.ChangeListener;
import org.netbeans.api.progress.ProgressHandle;
import org.netbeans.api.project.ProjectManager;
import org.netbeans.api.templates.TemplateRegistration;
import org.netbeans.spi.project.ui.support.ProjectChooser;
import org.openide.WizardDescriptor;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.NbBundle.Messages;

/** File &gt; New Project &gt; Godot &gt; Godot Project: creates a minimal Godot 4 project. */
@TemplateRegistration(
        folder = "Project/Godot",
        displayName = "#TPL_GodotProject_name",
        description = "GodotProjectDescription.html",
        iconBase = "io/ziumper/godot/godot-icon.png",
        position = 100)
@Messages({
    "TPL_GodotProject_name=Godot Project",
    "MSG_ProgressPrepare=Preparing project folder",
    "MSG_ProgressDetect=Detecting Godot version",
    "MSG_ProgressFiles=Creating project files",
    "MSG_ProgressRegister=Registering project",
    "MSG_ProgressDone=Finishing"
})
public final class GodotProjectWizardIterator implements WizardDescriptor.ProgressInstantiatingIterator<WizardDescriptor> {

    private WizardDescriptor wizard;
    private WizardDescriptor.Panel<WizardDescriptor> panel;

    @Override
    public void initialize(WizardDescriptor wiz) {
        this.wizard = wiz;
        this.panel = new GodotProjectWizardPanel();
        // Step labels shown in the wizard's left pane.
        wiz.putProperty("WizardPanel_contentData", new String[]{Bundle.TITLE_Location()});
        wiz.putProperty("WizardPanel_contentSelectedIndex", 0);
    }

    @Override
    public void uninitialize(WizardDescriptor wiz) {
        this.wizard = null;
        this.panel = null;
    }

    /** Not used: the wizard calls {@link #instantiate(ProgressHandle)} for progress iterators. */
    @Override
    public Set<FileObject> instantiate() throws IOException {
        throw new AssertionError("instantiate(ProgressHandle) should have been called");
    }

    @Override
    public Set<FileObject> instantiate(ProgressHandle handle) throws IOException {
        // Being a (Progress)AsynchronousInstantiatingIterator, this runs on a RequestProcessor thread, not the EDT.
        handle.start(4);
        handle.progress(Bundle.MSG_ProgressPrepare(), 0);
        File dir = (File) wizard.getProperty(GodotProjectWizardPanel.PROP_DIR);
        String name = (String) wizard.getProperty(GodotProjectWizardPanel.PROP_NAME);
        if (dir == null || name == null) {
            throw new IOException("Project name and location are not set");
        }
        File normalized = FileUtil.normalizeFile(dir);
        FileObject projectDir = FileUtil.createFolder(normalized);

        // Off the EDT (see above), so spawning the process is fine here.
        // If the version cannot be detected, config/features is omitted (Godot fills it in on first editor save).
        handle.progress(Bundle.MSG_ProgressDetect(), 1);
        String version = GodotVersion.detect().orElse(null);
        handle.progress(Bundle.MSG_ProgressFiles(), 2);
        write(projectDir, "project.godot", projectGodot(name, version));
        FileObject mainScene = write(projectDir, "main.tscn",
                "[gd_scene format=3]\n\n[node name=\"Main\" type=\"Node2D\"]\n");
        write(projectDir, "icon.svg", ICON_SVG);
        write(projectDir, ".gitignore", "# Godot 4+ specific ignores\n.godot/\n/android/\n");

        handle.progress(Bundle.MSG_ProgressRegister(), 3);
        File parent = normalized.getParentFile();
        if (parent != null) {
            ProjectChooser.setProjectsFolder(parent);
        }
        // The folder was not a project when first looked at; drop the cached negative answer.
        ProjectManager.getDefault().clearNonProjectCache();

        Set<FileObject> result = new LinkedHashSet<>();
        result.add(projectDir);
        result.add(mainScene);
        handle.progress(Bundle.MSG_ProgressDone(), 4);
        return result;
    }

    private static String projectGodot(String name, String version) {
        String escaped = name.replace("\\", "\\\\").replace("\"", "\\\"");
        return "; Engine configuration file.\n"
                + "; It's best edited using the editor UI and not directly,\n"
                + "; since the parameters that go here are not all obvious.\n"
                + ";\n"
                + "; Format:\n"
                + ";   [section] ; section goes between []\n"
                + ";   param=value ; assign values to parameters\n"
                + "\n"
                + "config_version=5\n"
                + "\n"
                + "[application]\n"
                + "\n"
                + "config/name=\"" + escaped + "\"\n"
                + "run/main_scene=\"res://main.tscn\"\n"
                + (version == null ? "" : "config/features=PackedStringArray(\"" + version + "\")\n")
                + "config/icon=\"res://icon.svg\"\n";
    }

    private static final String ICON_SVG
            = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"128\" height=\"128\" viewBox=\"0 0 128 128\">\n"
            + "  <rect width=\"128\" height=\"128\" rx=\"16\" fill=\"#363d52\"/>\n"
            + "  <circle cx=\"64\" cy=\"64\" r=\"36\" fill=\"#478cbf\"/>\n"
            + "</svg>\n";

    private static FileObject write(FileObject dir, String fileName, String content) throws IOException {
        FileObject fo = dir.getFileObject(fileName);
        if (fo == null) {
            fo = dir.createData(fileName);
        }
        try (OutputStream out = fo.getOutputStream()) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return fo;
    }

    @Override
    public WizardDescriptor.Panel<WizardDescriptor> current() {
        return panel;
    }

    @Override
    public String name() {
        return "1 of 1";
    }

    @Override
    public boolean hasNext() {
        return false;
    }

    @Override
    public boolean hasPrevious() {
        return false;
    }

    @Override
    public void nextPanel() {
        throw new NoSuchElementException();
    }

    @Override
    public void previousPanel() {
        throw new NoSuchElementException();
    }

    @Override
    public void addChangeListener(ChangeListener l) {
    }

    @Override
    public void removeChangeListener(ChangeListener l) {
    }
}
