package io.ziumper.godot.options;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import javax.swing.JComponent;
import io.ziumper.godot.lsp.GdscriptClient;
import io.ziumper.godot.lsp.GodotLspServer;
import io.ziumper.godot.lsp.process.StopReason;
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
        SettingsChange.Values before = currentValues();
        getPanel().store();
        changed = false;
        SettingsChange.Decision decision = SettingsChange.decide(before, currentValues());
        if (decision.stopServers()) {
            // Servers of the old mode or executable must not outlive the change.
            GodotLspServer.stopAll(StopReason.SETTINGS_CHANGED);
        }
        if (decision.reconnect()) {
            // The running connection still points at the old target.
            GdscriptClient.reconnect();
        }
    }

    private static SettingsChange.Values currentValues() {
        return new SettingsChange.Values(GodotSettings.isHeadlessLsp(), GodotSettings.getGodotExecutable(),
                GodotSettings.getHost(), GodotSettings.getPort(), GodotSettings.getConnectTimeoutMs());
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
