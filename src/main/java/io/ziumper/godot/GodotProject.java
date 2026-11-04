package io.ziumper.godot;

import org.netbeans.api.project.Project;
import org.netbeans.spi.project.ProjectState;
import org.openide.filesystems.FileObject;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;

public class GodotProject implements Project {

    private final FileObject dir;
    private final Lookup lookup;

    GodotProject(FileObject dir, ProjectState state) {
        this.dir = dir;
        // Built once: NetBeans expects a stable lookup per project instance.
        this.lookup = Lookups.fixed(
                this,
                state,
                new GodotProjectInformation(this),
                new GodotSources(new GodotSourceGroup(this)),
                new GodotLogicalViewProvider(this),
                new GodotActionProvider(this));
    }

    @Override
    public FileObject getProjectDirectory() {
        return dir;
    }

    @Override
    public Lookup getLookup() {
        return lookup;
    }

}
