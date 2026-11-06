package io.ziumper.godot.project;

import io.ziumper.godot.GodotIcons;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.Icon;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectInformation;
import org.openide.filesystems.FileChangeAdapter;
import org.openide.filesystems.FileEvent;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileRenameEvent;
import org.openide.util.ImageUtilities;
import org.openide.util.WeakListeners;

/** Project name and icon; the display name comes from config/name in project.godot. */
final class GodotProjectInformation implements ProjectInformation {

    private static final Pattern NAME_LINE
            = Pattern.compile("^\\s*config/name\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$");

    private static final String PROJECT_FILE = "project.godot";

    private final GodotProject project;
    private final PropertyChangeSupport support = new PropertyChangeSupport(this);
    private volatile String displayName;

    // Strong reference: the file system only holds the weak wrapper below.
    private final FileChangeAdapter fileListener = new FileChangeAdapter() {
        @Override
        public void fileChanged(FileEvent fe) {
            if (isProjectFile(fe.getFile())) {
                refresh();
            }
        }

        @Override
        public void fileDataCreated(FileEvent fe) {
            if ("project.godot".equals(fe.getFile().getNameExt())) {
                fe.getFile().addFileChangeListener(WeakListeners.create(
                        org.openide.filesystems.FileChangeListener.class, this, fe.getFile()));
                refresh();
            }
        }

        @Override
        public void fileDeleted(FileEvent fe) {
            if ("project.godot".equals(fe.getFile().getNameExt())) {
                refresh();
            }
        }

        @Override
        public void fileRenamed(FileRenameEvent fe) {
            // Either the old or the new name may be project.godot.
            if (isProjectFile(fe.getFile()) || PROJECT_FILE.equals(fe.getName() + (fe.getExt().isEmpty() ? "" : "." + fe.getExt()))) {
                refresh();
            }
        }
    };

    private static boolean isProjectFile(FileObject fo) {
        return PROJECT_FILE.equals(fo.getNameExt());
    }

    GodotProjectInformation(GodotProject project) {
        this.project = project;
        this.displayName = computeDisplayName();
        // The directory listener catches project.godot being created, deleted or replaced;
        // the file listener catches in-place edits.
        FileObject dir = project.getProjectDirectory();
        dir.addFileChangeListener(WeakListeners.create(
                org.openide.filesystems.FileChangeListener.class, fileListener, dir));
        FileObject cfg = dir.getFileObject("project.godot");
        if (cfg != null) {
            cfg.addFileChangeListener(WeakListeners.create(
                    org.openide.filesystems.FileChangeListener.class, fileListener, cfg));
        }
    }

    private String computeDisplayName() {
        String name = readConfigName(project.getProjectDirectory());
        return name != null ? name : project.getProjectDirectory().getNameExt();
    }

    /** Re-reads config/name and notifies listeners when the display name changed. */
    private void refresh() {
        String old = displayName;
        String fresh = computeDisplayName();
        if (!fresh.equals(old)) {
            displayName = fresh;
            support.firePropertyChange(ProjectInformation.PROP_DISPLAY_NAME, old, fresh);
        }
    }

    /** Reads config/name from the [application] section; returns null when missing or unreadable. */
    static String readConfigName(FileObject dir) {
        FileObject cfg = dir.getFileObject("project.godot");
        if (cfg == null) {
            return null;
        }
        try {
            List<String> lines = cfg.asLines("UTF-8");
            boolean inApplication = false;
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith("[")) {
                    inApplication = t.equals("[application]");
                    continue;
                }
                if (!inApplication) {
                    continue;
                }
                Matcher m = NAME_LINE.matcher(line);
                if (m.matches()) {
                    String v = unescape(m.group(1)).trim();
                    return v.isEmpty() ? null : v;
                }
            }
        } catch (IOException | RuntimeException ex) {
            // Tolerate a missing or malformed file; the caller falls back to the directory name.
        }
        return null;
    }

    /** Single-pass removal of backslash escapes (\\" and \\\\); other sequences are kept as written. */
    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && (s.charAt(i + 1) == '"' || s.charAt(i + 1) == '\\')) {
                c = s.charAt(++i);
            }
            sb.append(c);
        }
        return sb.toString();
    }

    @Override
    public String getName() {
        return project.getProjectDirectory().getNameExt();
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public Icon getIcon() {
        return ImageUtilities.loadImageIcon(GodotIcons.GODOT_ICON, false);
    }

    @Override
    public Project getProject() {
        return project;
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener pl) {
        support.addPropertyChangeListener(pl);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener pl) {
        support.removePropertyChangeListener(pl);
    }
}
