package io.ziumper.godot;

import java.awt.Component;
import java.io.File;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import org.openide.WizardDescriptor;
import org.openide.util.ChangeSupport;
import org.openide.util.HelpCtx;

/** Single wizard step: project name and location. */
final class GodotProjectWizardPanel implements WizardDescriptor.Panel<WizardDescriptor> {

    static final String PROP_NAME = "godotProjectName";
    static final String PROP_DIR = "godotProjectDir";

    private final ChangeSupport changes = new ChangeSupport(this);
    private GodotProjectVisualPanel component;
    private WizardDescriptor wizard;

    @Override
    public Component getComponent() {
        if (component == null) {
            component = new GodotProjectVisualPanel(this);
        }
        return component;
    }

    @Override
    public HelpCtx getHelp() {
        return HelpCtx.DEFAULT_HELP;
    }

    @Override
    public void readSettings(WizardDescriptor settings) {
        this.wizard = settings;
        ((GodotProjectVisualPanel) getComponent()).refreshState();
    }

    @Override
    public void storeSettings(WizardDescriptor settings) {
        GodotProjectVisualPanel panel = (GodotProjectVisualPanel) getComponent();
        settings.putProperty(PROP_NAME, panel.getProjectName());
        File dir = panel.getProjectDir();
        settings.putProperty(PROP_DIR, dir);
    }

    @Override
    public boolean isValid() {
        GodotProjectVisualPanel panel = (GodotProjectVisualPanel) getComponent();
        String error = panel.validateInput();
        if (wizard != null) {
            wizard.putProperty(WizardDescriptor.PROP_ERROR_MESSAGE, error);
        }
        return error == null;
    }

    void fireChange() {
        changes.fireChange();
    }

    @Override
    public void addChangeListener(ChangeListener l) {
        changes.addChangeListener(l);
    }

    @Override
    public void removeChangeListener(ChangeListener l) {
        changes.removeChangeListener(l);
    }
}
