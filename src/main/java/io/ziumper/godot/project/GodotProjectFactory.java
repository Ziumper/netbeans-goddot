package io.ziumper.godot.project;

/**
 *
 * @author ziumper
 */
import java.io.IOException;
import org.netbeans.api.project.Project;
import org.netbeans.spi.project.ProjectFactory;
import org.netbeans.spi.project.ProjectState;
import org.openide.filesystems.FileObject;
import org.openide.util.lookup.ServiceProvider;

@ServiceProvider(service = ProjectFactory.class)
public class GodotProjectFactory implements ProjectFactory {

    @Override
    public boolean isProject(FileObject dir) {
        FileObject cfg = dir.getFileObject("project.godot");
        return cfg != null && cfg.isData();
    }

    protected Project createProject(FileObject dir, ProjectState state) {
        return new GodotProject(dir, state);
    }

    @Override
    public Project loadProject(FileObject fo, ProjectState ps) throws IOException {
        // ProjectManager.findProject calls loadProject on every factory without asking isProject
        // first (FileOwnerQuery does that for each parent folder), so a non-Godot directory must
        // yield null. Otherwise e.g. a Maven project's src/main/java turns into a Godot project.
        if (!isProject(fo)) {
            return null;
        }
        return createProject(fo, ps);
    }

    @Override
    public void saveProject(Project prjct) throws IOException, ClassCastException {}
}
