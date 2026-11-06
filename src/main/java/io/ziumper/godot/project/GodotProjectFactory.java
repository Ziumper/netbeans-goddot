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
        return createProject(fo, ps);
    }

    @Override
    public void saveProject(Project prjct) throws IOException, ClassCastException {}
}
