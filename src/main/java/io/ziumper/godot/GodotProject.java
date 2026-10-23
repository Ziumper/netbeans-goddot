package io.ziumper.godot;

import java.beans.PropertyChangeListener;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectInformation;
import org.netbeans.spi.project.ProjectState;
import org.openide.filesystems.FileObject;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;

public class GodotProject implements Project {

    private final FileObject dir;

    GodotProject(FileObject dir, ProjectState state) {
        this.dir = dir;
    }

    @Override
    public FileObject getProjectDirectory() {
        return dir;
    }

    @Override
    public Lookup getLookup() {
        return Lookups.fixed(this, new ProjectInformation() {
            @Override
            public String getName() {
                return dir.getNameExt();
            }

            @Override
            public String getDisplayName() {
                return getName();
            }

            @Override
            public Icon getIcon() {
                return new ImageIcon(GodotProject.class.getResource(
                    "/io/ziumper/godot/godot-icon.png"));
            }

            @Override
            public Project getProject() {
                return GodotProject.this;  // ← nie throw
            }

            @Override
            public void addPropertyChangeListener(PropertyChangeListener pl) {
            }

            @Override
            public void removePropertyChangeListener(PropertyChangeListener pl) {
            }
        }, new GodotSourceGroup(this));
    }

}   