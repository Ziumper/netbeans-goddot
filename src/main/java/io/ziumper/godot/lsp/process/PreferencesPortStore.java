package io.ziumper.godot.lsp.process;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** {@link PortStore} in a preferences node; storage failures are only logged. */
final class PreferencesPortStore implements PortStore {

    private static final Logger LOG = Logger.getLogger(PreferencesPortStore.class.getName());
    private static final String PORT_KEY_PREFIX = "port.";

    private final Supplier<Preferences> node;

    PreferencesPortStore(Supplier<Preferences> node) {
        this.node = node;
    }

    @Override
    public int load(File projectDir) {
        return node.get().getInt(keyOf(projectDir), -1);
    }

    @Override
    public void save(File projectDir, int port) {
        Preferences prefs = node.get();
        prefs.putInt(keyOf(projectDir), port);
        try {
            prefs.flush();
        } catch (BackingStoreException ex) {
            LOG.log(Level.FINE, null, ex);
        }
    }

    @Override
    public Set<Integer> takenByOthers(File projectDir) {
        Preferences prefs = node.get();
        String own = keyOf(projectDir);
        Set<Integer> taken = new HashSet<>();
        try {
            for (String k : prefs.keys()) {
                if (k.startsWith(PORT_KEY_PREFIX) && !k.equals(own)) {
                    taken.add(prefs.getInt(k, -1));
                }
            }
        } catch (BackingStoreException ex) {
            LOG.log(Level.FINE, null, ex);
        }
        return taken;
    }

    /** Preferences keys are limited to 80 characters, so the path is stored as a hash. */
    private static String keyOf(File projectDir) {
        return PORT_KEY_PREFIX + sha1(projectDir.getAbsolutePath());
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex); // SHA-1 is required to exist on every JVM
        }
    }
}
