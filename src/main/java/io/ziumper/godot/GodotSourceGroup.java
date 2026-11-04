package io.ziumper.godot;

import javax.swing.Icon;
import org.netbeans.api.project.SourceGroup;
import org.openide.filesystems.FileObject;
import org.netbeans.api.queries.VisibilityQuery;
import org.openide.filesystems.FileUtil;
import org.openide.util.ImageUtilities;

public class GodotSourceGroup implements SourceGroup {

    private final GodotProject project;

    public GodotSourceGroup(GodotProject project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "Godot Scripts";
    }

    @Override
    public String getDisplayName() {
        return "Godot Scripts";
    }

    @Override
    public void addPropertyChangeListener(java.beans.PropertyChangeListener pl) {}

    @Override
    public void removePropertyChangeListener(java.beans.PropertyChangeListener pl) {}

    @Override
    public FileObject getRootFolder() {
       return project.getProjectDirectory(); 
    }

    @Override
    public Icon getIcon(boolean bln) {
        return ImageUtilities.loadImageIcon(GodotProjectInformation.ICON_PATH, false);
    }

    @Override
    public boolean contains(FileObject fo) {
        FileObject root = project.getProjectDirectory();
        if (fo != root && !FileUtil.isParentOf(root, fo)) {
            return false;
        }
        // Reject anything below a hidden/ignored path element (.godot, *.import, VisibilityQuery).
        for (FileObject f = fo; f != null && !f.equals(root); f = f.getParent()) {
            if (GodotLogicalViewProvider.isHidden(f) || !VisibilityQuery.getDefault().isVisible(f)) {
                return false;
            }
        }
        return true;
    }
}