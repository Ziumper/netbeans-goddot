package io.ziumper.godot.project;

import io.ziumper.godot.engine.GodotExecutables;

import io.ziumper.godot.options.GodotSettings;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.netbeans.api.extexecution.ExecutionDescriptor;
import org.netbeans.api.extexecution.ExecutionService;
import org.netbeans.api.project.ProjectInformation;
import org.netbeans.spi.project.ActionProvider;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.util.Lookup;

/**
 * Run / Run single (.tscn or SceneTree/MainLoop .gd) via the configured Godot executable.
 * Enablement checks stay cheap (file extension only); the script content is inspected on invocation.
 */
final class GodotActionProvider implements ActionProvider {

    private static final String[] SUPPORTED = {COMMAND_RUN, COMMAND_RUN_SINGLE};

    private final GodotProject project;

    GodotActionProvider(GodotProject project) {
        this.project = project;
    }

    @Override
    public String[] getSupportedActions() {
        return SUPPORTED.clone();
    }

    @Override
    public boolean isActionEnabled(String command, Lookup context) {
        if (COMMAND_RUN.equals(command)) {
            return true;
        }
        if (COMMAND_RUN_SINGLE.equals(command)) {
            return runnable(context) != null;
        }
        return false;
    }

    @Override
    public void invokeAction(String command, Lookup context) {
        File projectDir = FileUtil.toFile(project.getProjectDirectory());
        if (projectDir == null) {
            notify("The project directory is not on the local disk; cannot run it with Godot.");
            return;
        }
        String scene = null;
        boolean script = false;
        if (COMMAND_RUN_SINGLE.equals(command)) {
            FileObject fo = runnable(context);
            if (fo == null) {
                return;
            }
            scene = "res://" + FileUtil.getRelativePath(project.getProjectDirectory(), fo);
            script = "gd".equalsIgnoreCase(fo.getExt());
            if (script && !isMainLoopScript(fo)) {
                notify("Only scenes (.tscn) and scripts extending SceneTree or MainLoop can be run on their own.\n"
                        + scene + " does not extend SceneTree or MainLoop.");
                return;
            }
        } else if (!COMMAND_RUN.equals(command)) {
            return;
        }

        String exe = GodotSettings.getGodotExecutable();
        if (!isExecutable(exe)) {
            notify("Godot executable \"" + exe + "\" was not found or is not executable.\n"
                    + "Set the preference \"" + GodotSettings.PROP_GODOT_EXECUTABLE
                    + "\" (GodotSettings) or put godot on PATH.");
            return;
        }

        // Scenes are passed as a positional argument; SceneTree/MainLoop scripts need -s.
        final String[] args = scene == null
                ? new String[]{"--path", projectDir.getAbsolutePath()}
                : script
                ? new String[]{"--path", projectDir.getAbsolutePath(), "-s", scene}
                : new String[]{"--path", projectDir.getAbsolutePath(), scene};
        Callable<Process> processCreator = () -> {
            ProcessBuilder pb = new ProcessBuilder();
            pb.command().add(exe);
            for (String a : args) {
                pb.command().add(a);
            }
            pb.directory(projectDir);
            pb.redirectErrorStream(false);
            return pb.start();
        };

        ProjectInformation info = project.getLookup().lookup(ProjectInformation.class);
        String title = info.getDisplayName() + (scene == null ? "" : " (" + scene + ")");
        ExecutionDescriptor descriptor = new ExecutionDescriptor()
                .controllable(true)
                .frontWindow(true)
                .showProgress(true)
                .showSuspended(false);
        ExecutionService.newService(processCreator, descriptor, title).run();
    }

    /**
 * The single .tscn or .gd file selected in the context, or null. Only the extension is checked here,
 * because this runs on every selection change; whether a .gd is a SceneTree/MainLoop script is
 * checked in {@link #invokeAction}.
 */
    private FileObject runnable(Lookup context) {
        Collection<? extends DataObject> dobjs = context.lookupAll(DataObject.class);
        Collection<? extends FileObject> fos = context.lookupAll(FileObject.class);
        FileObject found = null;
        int count = 0;
        for (DataObject d : dobjs) {
            found = d.getPrimaryFile();
            count++;
        }
        if (count == 0) {
            for (FileObject f : fos) {
                found = f;
                count++;
            }
        }
        if (count != 1 || found == null || !FileUtil.isParentOf(project.getProjectDirectory(), found)) {
            return null;
        }
        String ext = found.getExt();
        return "tscn".equalsIgnoreCase(ext) || "gd".equalsIgnoreCase(ext) ? found : null;
    }

    private static final Pattern EXTENDS_LINE = Pattern.compile("^(?:class_name\\s+\\w+\\s+)?extends\\s+(\\w+)\\s*(?:#.*)?$");

    /**
     * True when the first non-blank, non-comment line of the script that starts with {@code extends}
     * names SceneTree or MainLoop. Reads at most the first 64 KiB; only the head is needed.
     */
    static boolean isMainLoopScript(FileObject fo) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(fo.getInputStream(), StandardCharsets.UTF_8))) {
            int budget = 65536;
            String line;
            while ((line = r.readLine()) != null && budget > 0) {
                budget -= line.length() + 1;
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("@") || t.matches("class_name\\s+\\w+\\s*(?:#.*)?")) {
                    // Blank, comment, annotation (@tool, @icon, ...) or lone class_name lines may precede "extends".
                    continue;
                }
                Matcher m = EXTENDS_LINE.matcher(t);
                if (m.matches()) {
                    return "SceneTree".equals(m.group(1)) || "MainLoop".equals(m.group(1));
                }
                // "extends" is not first (or is not a plain class name); stop looking.
                return false;
            }
        } catch (IOException ex) {
            // unreadable: treat as not runnable
        }
        return false;
    }

    private static boolean isExecutable(String exe) {
        return GodotExecutables.isExecutable(exe);
    }

    private static void notify(String message) {
        DialogDisplayer.getDefault().notify(
                new NotifyDescriptor.Message(message, NotifyDescriptor.WARNING_MESSAGE));
    }
}
