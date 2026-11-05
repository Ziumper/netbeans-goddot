package io.ziumper.godot.options;

import io.ziumper.godot.engine.GodotVersion;

import io.ziumper.godot.engine.GodotExecutables;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Color;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Optional;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.openide.filesystems.FileChooserBuilder;
import org.openide.util.NbBundle.Messages;
import org.openide.util.RequestProcessor;

/** Hand-written Swing form for {@link GodotOptionsPanelController}. */
@Messages({
    "LBL_GodotExecutable=Godot executable:",
    "LBL_Browse=Browse...",
    "LBL_LspHost=LSP host:",
    "LBL_LspPort=LSP port:",
    "LBL_ConnectTimeout=Connect timeout (ms):",
    "LBL_AutoReconnect=Reconnect automatically",
    "LBL_RetryInterval=Retry interval (ms):",
    "LBL_SelectGodot=Select Godot executable",
    "MSG_ExecutableWarning=Warning: not an executable file and not found on PATH - Run and version detection won't work.",
    "MSG_ExecutableEmptyWarning=Warning: empty path, the default \"godot\" from PATH will be used, but it was not found - Run and version detection won't work.",
    "MSG_HostEmpty=LSP host must not be empty.",
    "MSG_PortRange=LSP port must be a number between 1 and 65535.",
    "MSG_TimeoutInvalid=Connect timeout must be a positive number.",
    "MSG_RetryInvalid=Retry interval must be a positive number.",
    "LBL_TestConnection=Test connection",
    "MSG_Testing=Testing...",
    "# {0} - version",
    "MSG_GodotFound=Godot {0} found",
    "MSG_GodotNotFound=Godot executable could not be run or its version detected",
    "# {0} - host:port",
    "MSG_LspReachable=LSP at {0} reachable",
    "# {0} - host:port",
    "MSG_LspUnreachable=LSP at {0} not reachable (open the project in the Godot Editor)",
    "MSG_TestInvalidInput=Fix the invalid host/port first"
})
final class GodotOptionsPanel extends JPanel {

    private final JTextField executable = new JTextField(30);
    private final JTextField host = new JTextField(20);
    private final JSpinner port = new JSpinner(new SpinnerNumberModel(GodotSettings.DEFAULT_PORT, 0, 99999, 1));
    private final JSpinner timeout = new JSpinner(new SpinnerNumberModel(GodotSettings.DEFAULT_CONNECT_TIMEOUT, 0, 600000, 100));
    private final JCheckBox autoReconnect = new JCheckBox(Bundle.LBL_AutoReconnect());
    private final JSpinner retry = new JSpinner(new SpinnerNumberModel(GodotSettings.DEFAULT_RETRY_INTERVAL, 0, 3600000, 500));

    private static final RequestProcessor RP = new RequestProcessor("Godot options test", 1);
    /** Non-blocking hint about the Godot path; never affects {@link #valid()}. */
    private final JLabel executableWarning = new JLabel(" ");
    /** Blocking error for genuinely invalid values; shown when {@link #valid()} is false. */
    private final JLabel validationError = new JLabel(" ");
    private final JLabel testStatus = new JLabel(" ");
    private final JButton testButton = new JButton(Bundle.LBL_TestConnection());

    GodotOptionsPanel(ChangeListener changes) {
        setLayout(new GridBagLayout());
        JButton browse = new JButton(Bundle.LBL_Browse());
        browse.addActionListener(e -> browse());

        addRow(0, Bundle.LBL_GodotExecutable(), executable, browse);
        executableWarning.setForeground(new Color(0xB3, 0x6B, 0x00));
        GridBagConstraints ec = new GridBagConstraints();
        ec.gridx = 1;
        ec.gridy = 1;
        ec.gridwidth = 2;
        ec.anchor = GridBagConstraints.WEST;
        ec.insets = new Insets(0, 4, 4, 4);
        add(executableWarning, ec);
        addRow(2, Bundle.LBL_LspHost(), host, null);
        addRow(3, Bundle.LBL_LspPort(), port, null);
        addRow(4, Bundle.LBL_ConnectTimeout(), timeout, null);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = 5;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 4, 4, 4);
        add(autoReconnect, c);
        addRow(6, Bundle.LBL_RetryInterval(), retry, null);
        validationError.setForeground(new Color(0xC0, 0x30, 0x30));
        GridBagConstraints vc = new GridBagConstraints();
        vc.gridx = 1;
        vc.gridy = 7;
        vc.gridwidth = 2;
        vc.anchor = GridBagConstraints.WEST;
        vc.insets = new Insets(0, 4, 4, 4);
        add(validationError, vc);
        testButton.addActionListener(e -> testConnection());
        GridBagConstraints tc = new GridBagConstraints();
        tc.gridx = 0;
        tc.gridy = 8;
        tc.anchor = GridBagConstraints.WEST;
        tc.insets = new Insets(8, 4, 4, 4);
        add(testButton, tc);
        GridBagConstraints sc = new GridBagConstraints();
        sc.gridx = 1;
        sc.gridy = 8;
        sc.gridwidth = 2;
        sc.anchor = GridBagConstraints.WEST;
        sc.insets = new Insets(8, 4, 4, 4);
        add(testStatus, sc);
        // Absorb extra vertical space so the rows stay at the top.
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = 9;
        filler.weighty = 1;
        add(new JPanel(), filler);

        DocumentListener dl = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateValidationError();
                changes.stateChanged(null);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateValidationError();
                changes.stateChanged(null);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateValidationError();
                changes.stateChanged(null);
            }
        };
        executable.getDocument().addDocumentListener(dl);
        executable.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateExecutableError(); }
            @Override public void removeUpdate(DocumentEvent e) { updateExecutableError(); }
            @Override public void changedUpdate(DocumentEvent e) { updateExecutableError(); }
        });
        host.getDocument().addDocumentListener(dl);
        ChangeListener spinnerChanges = e -> {
            updateValidationError();
            changes.stateChanged(e);
        };
        port.addChangeListener(spinnerChanges);
        timeout.addChangeListener(spinnerChanges);
        retry.addChangeListener(spinnerChanges);
        autoReconnect.addChangeListener(changes);
    }

    private void addRow(int row, String label, java.awt.Component field, JButton button) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = field instanceof JSpinner ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
        add(field, c);
        if (button != null) {
            c.gridx = 2;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            add(button, c);
        }
    }

    private void browse() {
        FileChooserBuilder builder = new FileChooserBuilder("godot-executable")
                .setFilesOnly(true)
                .setTitle(Bundle.LBL_SelectGodot());
        File current = new File(executable.getText().trim());
        if (current.isAbsolute()) {
            File dir = current.getParentFile();
            if (dir != null && dir.isDirectory()) {
                builder.setDefaultWorkingDirectory(dir);
            }
        }
        File chosen = builder.showOpenDialog();
        if (chosen != null) {
            executable.setText(chosen.getAbsolutePath());
        }
    }

    /** The path that will actually be used: an empty field falls back to the default. */
    private String effectiveExecutable() {
        String exe = executable.getText().trim();
        return exe.isEmpty() ? GodotSettings.DEFAULT_GODOT_EXECUTABLE : exe;
    }

    /** Updates the non-blocking Godot path warning (never affects validity). */
    private void updateExecutableError() {
        if (GodotExecutables.isExecutable(effectiveExecutable())) {
            executableWarning.setText(" ");
            executableWarning.setIcon(null);
            return;
        }
        executableWarning.setIcon(UIManager.getIcon("OptionPane.warningIcon"));
        executableWarning.setText(executable.getText().trim().isEmpty()
                ? Bundle.MSG_ExecutableEmptyWarning() : Bundle.MSG_ExecutableWarning());
    }

    /** Returns the first blocking validation message, or null when all values are valid. */
    private String validationMessage() {
        int p = intOf(port);
        if (host.getText().trim().isEmpty()) {
            return Bundle.MSG_HostEmpty();
        }
        if (p < 1 || p > 65535) {
            return Bundle.MSG_PortRange();
        }
        if (intOf(timeout) <= 0) {
            return Bundle.MSG_TimeoutInvalid();
        }
        if (intOf(retry) <= 0) {
            return Bundle.MSG_RetryInvalid();
        }
        return null;
    }

    private void updateValidationError() {
        String msg = validationMessage();
        validationError.setText(msg == null ? " " : msg);
    }

    /** Runs the checks on a background thread using the current (unsaved) field values. */
    private void testConnection() {
        final String exe = executable.getText().trim();
        final String h = host.getText().trim();
        final int p = intOf(port);
        final int t = intOf(timeout);
        if (h.isEmpty() || p < 1 || p > 65535) {
            testStatus.setText(Bundle.MSG_TestInvalidInput());
            return;
        }
        testButton.setEnabled(false);
        testStatus.setText(Bundle.MSG_Testing());
        RP.post(() -> {
            Optional<String> version = exe.isEmpty() ? Optional.empty() : GodotVersion.detect(exe);
            boolean reachable = false;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(h, p), Math.max(100, t));
                reachable = true;
            } catch (java.io.IOException | RuntimeException ex) {
                // not reachable (RuntimeException covers an unresolvable/illegal address)
            }
            String hostPort = h + ":" + p;
            String msg = version.map(Bundle::MSG_GodotFound).orElse(Bundle.MSG_GodotNotFound())
                    + "; " + (reachable ? Bundle.MSG_LspReachable(hostPort) : Bundle.MSG_LspUnreachable(hostPort));
            SwingUtilities.invokeLater(() -> {
                testStatus.setText(msg);
                testButton.setEnabled(true);
            });
        });
    }

    void load() {
        executable.setText(GodotSettings.getGodotExecutable());
        host.setText(GodotSettings.getHost());
        port.setValue(GodotSettings.getPort());
        timeout.setValue(GodotSettings.getConnectTimeoutMs());
        autoReconnect.setSelected(GodotSettings.isAutoReconnect());
        retry.setValue(GodotSettings.getRetryIntervalMs());
        updateExecutableError();
        updateValidationError();
    }

    void store() {
        // An empty field is stored as-is; GodotSettings falls back to the default on read.
        GodotSettings.setGodotExecutable(executable.getText().trim());
        GodotSettings.setHost(host.getText().trim());
        GodotSettings.setPort(intOf(port));
        GodotSettings.setConnectTimeoutMs(intOf(timeout));
        GodotSettings.setAutoReconnect(autoReconnect.isSelected());
        GodotSettings.setRetryIntervalMs(intOf(retry));
    }

    boolean valid() {
        // The Godot path is deliberately not checked here: it only produces a warning.
        return validationMessage() == null;
    }

    /** True when any field differs from the stored (normalized) settings. */
    boolean changed() {
        return !effectiveExecutable().equals(GodotSettings.getGodotExecutable())
                || !host.getText().trim().equals(GodotSettings.getHost())
                || intOf(port) != GodotSettings.getPort()
                || intOf(timeout) != GodotSettings.getConnectTimeoutMs()
                || autoReconnect.isSelected() != GodotSettings.isAutoReconnect()
                || intOf(retry) != GodotSettings.getRetryIntervalMs();
    }

    private static int intOf(JSpinner s) {
        return ((Number) s.getValue()).intValue();
    }
}
