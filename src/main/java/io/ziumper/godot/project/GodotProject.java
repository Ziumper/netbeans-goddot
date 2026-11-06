package io.ziumper.godot.project;

import org.netbeans.api.project.Project;
import org.netbeans.spi.project.ProjectState;
import org.netbeans.spi.project.ui.ProjectOpenedHook;
import io.ziumper.godot.lsp.GodotLspServer;
import java.io.File;
import org.openide.filesystems.FileUtil;
import org.openide.util.RequestProcessor;
import org.openide.filesystems.FileObject;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;

public class GodotProject implements Project {

    private static final RequestProcessor RP = new RequestProcessor(GodotProject.class);

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
                        // The headless LSP is started on demand by the first .gd editor.
                    }

                    @Override
                    protected void projectClosed() {
                        // Stopping waits for the process; keep that off the caller's thread.
                        File root = FileUtil.toFile(GodotProject.this.dir);
                        if (root != null) {
                            RP.post(() -> GodotLspServer.stop(root));
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
