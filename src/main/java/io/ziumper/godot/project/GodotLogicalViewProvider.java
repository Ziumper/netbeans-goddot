package io.ziumper.godot.project;

import io.ziumper.godot.GodotIcons;
import java.awt.Image;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import javax.swing.Action;
import org.netbeans.api.project.ProjectInformation;
import org.netbeans.spi.project.ActionProvider;
import org.netbeans.spi.project.ui.LogicalViewProvider;
import org.netbeans.spi.project.ui.support.CommonProjectActions;
import org.netbeans.spi.project.ui.support.ProjectSensitiveActions;
import org.openide.awt.Actions;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataFolder;
import org.openide.loaders.DataObject;
import org.openide.nodes.AbstractNode;
import org.openide.nodes.FilterNode;
import org.openide.nodes.Node;
import org.openide.util.ImageUtilities;
import org.openide.util.WeakListeners;
import org.openide.util.lookup.Lookups;
import org.openide.util.lookup.ProxyLookup;

/** Logical view: the project directory with Godot housekeeping files hidden. */
public class GodotLogicalViewProvider implements LogicalViewProvider {

    private final GodotProject project;

    GodotLogicalViewProvider(GodotProject project) {
        this.project = project;
    }

    /** True for dot-files/dirs (.godot, .git, ...) and Godot *.import files. */
    static boolean isHidden(FileObject fo) {
        String name = fo.getNameExt();
        return name.startsWith(".") || name.endsWith(".import");
    }

    @Override
    public Node createLogicalView() {
        FileObject dir = project.getProjectDirectory();
        DataFolder folder = DataFolder.findFolder(dir);
        return new RootNode(folder.getNodeDelegate(), project);
    }

    @Override
    public Node findPath(Node root, Object target) {
        FileObject fo;
        if (target instanceof FileObject) {
            fo = (FileObject) target;
        } else if (target instanceof DataObject) {
            fo = ((DataObject) target).getPrimaryFile();
        } else {
            return null;
        }
        FileObject dir = project.getProjectDirectory();
        if (fo.equals(dir)) {
            return root;
        }
        String rel = FileUtil.getRelativePath(dir, fo);
        if (rel == null) {
            return null;
        }
        Node current = root;
        for (String segment : rel.split("/")) {
            Node next = null;
            for (Node child : current.getChildren().getNodes(true)) {
                FileObject cf = fileOf(child);
                if (cf != null && cf.getNameExt().equals(segment)) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    private static FileObject fileOf(Node node) {
        DataObject dobj = node.getLookup().lookup(DataObject.class);
        if (dobj != null) {
            return dobj.getPrimaryFile();
        }
        return node.getLookup().lookup(FileObject.class);
    }

    private static final class RootNode extends FilterNode {

        private final GodotProject project;
        // Strong reference, otherwise the weak wrapper would lose its target.
        private final PropertyChangeListener infoListener = this::onInfoChange;

        RootNode(Node original, GodotProject project) {
            super(original, new HidingChildren(original),
                    new ProxyLookup(original.getLookup(), Lookups.singleton(project)));
            this.project = project;
            // Weak listener: the project (and its information) outlives the node.
            ProjectInformation info = project.getLookup().lookup(ProjectInformation.class);
            info.addPropertyChangeListener(WeakListeners.propertyChange(infoListener, info));
        }

        private void onInfoChange(PropertyChangeEvent ev) {
            if (ProjectInformation.PROP_DISPLAY_NAME.equals(ev.getPropertyName())) {
                fireDisplayNameChange(null, null);
            }
        }

        @Override
        public String getDisplayName() {
            return project.getLookup().lookup(ProjectInformation.class).getDisplayName();
        }

        @Override
        public String getName() {
            return project.getProjectDirectory().getNameExt();
        }

        @Override
        public Image getIcon(int type) {
            return ImageUtilities.loadImage(GodotIcons.GODOT_ICON, true);
        }

        @Override
        public Image getOpenedIcon(int type) {
            return getIcon(type);
        }

        @Override
        public Action[] getActions(boolean context) {
            Action find = Actions.forID("Edit", "org.netbeans.modules.search.FindInFilesAction");
            return new Action[]{
                ProjectSensitiveActions.projectCommandAction(ActionProvider.COMMAND_RUN, "Run", null),
                null,
                CommonProjectActions.newFileAction(),
                null,
                find,
                null,
                CommonProjectActions.closeProjectAction()
            };
        }
    }

    /** Children of a folder node without hidden entries; recursive for sub-folders. */
    private static final class HidingChildren extends FilterNode.Children {

        HidingChildren(Node original) {
            super(original);
        }

        @Override
        protected Node[] createNodes(Node key) {
            FileObject fo = fileOf(key);
            if (fo != null && isHidden(fo)) {
                return new Node[0];
            }
            return super.createNodes(key);
        }

        @Override
        protected Node copyNode(Node original) {
            if (original.isLeaf()) {
                return super.copyNode(original);
            }
            return new FilterNode(original, new HidingChildren(original));
        }
    }
}
