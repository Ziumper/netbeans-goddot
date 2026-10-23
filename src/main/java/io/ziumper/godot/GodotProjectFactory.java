/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package io.ziumper.godot;

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
        return dir.getChildren().length > 0 &&
               dir.getFileObject("project.godot") != null;
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
