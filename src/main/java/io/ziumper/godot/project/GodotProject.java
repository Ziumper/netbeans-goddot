package io.ziumper.godot.project;

import org.netbeans.api.project.Project;
import org.netbeans.spi.project.ProjectState;
import org.netbeans.spi.project.ui.ProjectOpenedHook;
import io.ziumper.godot.lsp.GdscriptClient;
import io.ziumper.godot.lsp.GodotLspServer;
import java.io.File;
import org.openide.filesystems.FileUtil;
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
                new GodotActionProvider(this),
                new ProjectOpenedHook() {
                    @Override
                    protected void projectOpened() {
                        // Synchronous and cheap (may run on the EDT): lifts the "closed" mark so the
                        // hooks' order decides the state even for a quick close -> open. The headless LSP
                        // itself is started on demand by the .gd editors; those refused while the project
                        // was closed are restarted here, off this thread.
                        File root = FileUtil.toFile(GodotProject.this.dir);
                        if (root != null) {
                            GodotLspServer.allow(root);
                            GdscriptClient.projectAllowed(root);
                        }
                    }

                    @Override
                    protected void projectClosed() {
                        // Marks the project closed and detaches its server synchronously (cheap, no waiting);
                        // the kill itself runs on a background thread inside close().
                        File root = FileUtil.toFile(GodotProject.this.dir);
                        if (root != null) {
                            GodotLspServer.close(root);
                        }
                    }
                });
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
