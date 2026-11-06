package io.ziumper.godot.options;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import javax.swing.JComponent;
import io.ziumper.godot.lsp.GdscriptClient;
import org.netbeans.spi.options.OptionsPanelController;
import org.openide.util.HelpCtx;
import org.openide.util.Lookup;
import org.openide.util.NbBundle.Messages;

/** Tools > Options > Miscellaneous > Godot. */
@Messages("LBL_GodotOptions=Godot")
@OptionsPanelController.SubRegistration(
        id = "Godot",
        displayName = "#LBL_GodotOptions",
        location = "Advanced")
public final class GodotOptionsPanelController extends OptionsPanelController {

    private GodotOptionsPanel panel;
    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private boolean changed;

    @Override
    public void update() {
        getPanel().load();
        changed = false;
    }

    @Override
    public void applyChanges() {
        String host = GodotSettings.getHost();
        int port = GodotSettings.getPort();
        int timeout = GodotSettings.getConnectTimeoutMs();
        getPanel().store();
        changed = false;
        if (!host.equals(GodotSettings.getHost()) || port != GodotSettings.getPort()
                || timeout != GodotSettings.getConnectTimeoutMs()) {
            // The running connection still points at the old target.
            GdscriptClient.reconnect();
        }
    }

    @Override
    public void cancel() {
        // Nothing stored until applyChanges.
    }

    @Override
    public boolean isValid() {
        return getPanel().valid();
    }

    @Override
    public boolean isChanged() {
        return getPanel().changed();
    }

    @Override
    public JComponent getComponent(Lookup masterLookup) {
        return getPanel();
    }

    @Override
    public HelpCtx getHelpCtx() {
        return null;
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener l) {
        pcs.addPropertyChangeListener(l);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener l) {
        pcs.removePropertyChangeListener(l);
    }

    private GodotOptionsPanel getPanel() {
        if (panel == null) {
            panel = new GodotOptionsPanel(e -> fireChanged());
        }
        return panel;
    }

    private void fireChanged() {
        boolean now = panel.changed();
        if (now != changed) {
            changed = now;
            pcs.firePropertyChange(OptionsPanelController.PROP_CHANGED, !now, now);
        }
        pcs.firePropertyChange(OptionsPanelController.PROP_VALID, null, null);
    }
}
