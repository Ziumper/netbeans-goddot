package io.ziumper.godot;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.netbeans.spi.project.ui.support.ProjectChooser;
import org.openide.filesystems.FileChooserBuilder;
import org.openide.util.NbBundle.Messages;

/** UI of the single wizard step; built by hand to keep the module free of form-editor files. */
@Messages({
    "LBL_WizProjectName=Project &Name:",
    "LBL_WizProjectLocation=Project &Location:",
    "LBL_WizProjectFolder=Project &Folder:",
    "LBL_WizBrowse=Br&owse...",
    "LBL_WizSelectLocation=Select Project Location",
    "TITLE_Location=Name and Location",
    "MSG_NameEmpty=Project name must not be empty.",
    "MSG_NameInvalid=Project name is not a valid folder name.",
    "MSG_LocationInvalid=Project location must be an existing folder.",
    "MSG_FolderNotEmpty=Project folder already exists and is not empty.",
    "MSG_FolderIsFile=Project folder path points to an existing file."
})
final class GodotProjectVisualPanel extends JPanel {

    private final GodotProjectWizardPanel owner;
    private final JTextField nameField = new JTextField("GodotProject", 30);
    private final JTextField locationField = new JTextField(30);
    private final JTextField folderField = new JTextField(30);

    GodotProjectVisualPanel(GodotProjectWizardPanel owner) {
        this.owner = owner;
        setName(Bundle.TITLE_Location());
        folderField.setEditable(false);
        File base = ProjectChooser.getProjectsFolder();
        locationField.setText(base.getAbsolutePath());

        setLayout(new GridBagLayout());
        JLabel nameLabel = new JLabel();
        org.openide.awt.Mnemonics.setLocalizedText(nameLabel, Bundle.LBL_WizProjectName());
        nameLabel.setLabelFor(nameField);
        JLabel locLabel = new JLabel();
        org.openide.awt.Mnemonics.setLocalizedText(locLabel, Bundle.LBL_WizProjectLocation());
        locLabel.setLabelFor(locationField);
        JLabel folderLabel = new JLabel();
        org.openide.awt.Mnemonics.setLocalizedText(folderLabel, Bundle.LBL_WizProjectFolder());
        folderLabel.setLabelFor(folderField);
        JButton browse = new JButton();
        org.openide.awt.Mnemonics.setLocalizedText(browse, Bundle.LBL_WizBrowse());
        browse.addActionListener(e -> browse());

        add(nameLabel, c(0, 0, 0));
        add(nameField, c(1, 0, 1));
        add(locLabel, c(0, 1, 0));
        add(locationField, c(1, 1, 1));
        add(browse, c(2, 1, 0));
        add(folderLabel, c(0, 2, 0));
        add(folderField, c(1, 2, 1));
        // Filler pushes the rows to the top.
        GridBagConstraints filler = c(0, 3, 0);
        filler.weighty = 1;
        filler.gridwidth = 3;
        add(new JPanel(), filler);

        DocumentListener dl = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        };
        nameField.getDocument().addDocumentListener(dl);
        locationField.getDocument().addDocumentListener(dl);
        updateFolder();
    }

    private static GridBagConstraints c(int x, int y, double weightx) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = x;
        gc.gridy = y;
        gc.weightx = weightx;
        gc.fill = weightx > 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(4, 4, 4, 4);
        return gc;
    }

    private void browse() {
        File start = new File(locationField.getText().trim());
        FileChooserBuilder builder = new FileChooserBuilder("godot-project-location")
                .setDirectoriesOnly(true)
                .setTitle(Bundle.LBL_WizSelectLocation());
        if (start.isDirectory()) {
            builder.setDefaultWorkingDirectory(start);
        }
        File chosen = builder.showOpenDialog();
        if (chosen != null) {
            locationField.setText(chosen.getAbsolutePath());
        }
    }

    private void changed() {
        updateFolder();
        owner.fireChange();
    }

    private void updateFolder() {
        File dir = getProjectDir();
        folderField.setText(dir == null ? "" : dir.getPath());
    }

    void refreshState() {
        updateFolder();
    }

    String getProjectName() {
        return nameField.getText().trim();
    }

    /** Resulting project folder, or null when the inputs cannot form a path. */
    File getProjectDir() {
        String name = getProjectName();
        String loc = locationField.getText().trim();
        if (name.isEmpty() || loc.isEmpty()) {
            return null;
        }
        return new File(loc, name);
    }

    /** Returns an error message or null when the input is valid. */
    String validateInput() {
        String name = getProjectName();
        if (name.isEmpty()) {
            return Bundle.MSG_NameEmpty();
        }
        if (name.equals(".") || name.equals("..") || name.matches(".*[/\\\\:*?\"<>|\\x00-\\x1f].*")) {
            return Bundle.MSG_NameInvalid();
        }
        File loc = new File(locationField.getText().trim());
        if (locationField.getText().trim().isEmpty() || !loc.isDirectory()) {
            return Bundle.MSG_LocationInvalid();
        }
        File dir = new File(loc, name);
        if (dir.isFile()) {
            return Bundle.MSG_FolderIsFile();
        }
        if (dir.isDirectory()) {
            String[] children = dir.list();
            if (children == null || children.length > 0) {
                return Bundle.MSG_FolderNotEmpty();
            }
        }
        return null;
    }
}
