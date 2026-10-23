package io.ziumper.godot;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import org.netbeans.api.project.SourceGroup;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

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
        return new ImageIcon(GodotProject.class.getResource(
                    "/io/ziumper/godot/godot-icon.png"));
    }

    @Override
    public boolean contains(FileObject fo) {
        return FileUtil.isParentOf(project.getProjectDirectory(), fo);
    }
}