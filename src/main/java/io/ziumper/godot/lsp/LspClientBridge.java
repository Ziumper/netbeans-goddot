package io.ziumper.godot.lsp;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.api.project.Project;
import org.netbeans.modules.lsp.client.spi.LanguageServerProvider;

/**
 * The only place that reaches into lsp.client internals. lsp.client builds the bindings of a server only when it
 * needs them (a file is opened, edited or closed, or its indexer runs), and its SPI has no way to ask for that. A
 * headless server started in the background would then stay unused until the next keystroke, so once it is up we
 * ask lsp.client for the project's server the way its own indexer does, through
 * {@code LSPBindings.ensureServerRunning(Project, String)}. If a NetBeans version drops that method, the editors
 * connect on their next request instead.
 */
final class LspClientBridge {

    private static final Logger LOG = Logger.getLogger(LspClientBridge.class.getName());
    private static final String BINDINGS = "org.netbeans.modules.lsp.client.LSPBindings";

    private LspClientBridge() {
    }

    /** Resolved once, on first use. */
    private static final class Holder {

        static final Method ENSURE_SERVER_RUNNING = find();
    }

    /**
     * Makes lsp.client build the bindings of the project's server for the MIME type now, which calls the
     * provider's startServer on this thread. Blocks while lsp.client initializes the server, so never call it on
     * the EDT. Failures are only logged.
     */
    static void ensureServerRunning(Project prj, String mimeType) {
        Method m = Holder.ENSURE_SERVER_RUNNING;
        if (m == null) {
            return;
        }
        try {
            m.invoke(null, prj, mimeType);
        } catch (InvocationTargetException ex) {
            LOG.log(Level.INFO, "lsp.client could not start the " + mimeType + " server of "
                    + prj.getProjectDirectory().getPath(), ex.getCause());
        } catch (IllegalAccessException | RuntimeException ex) {
            LOG.log(Level.FINE, "Could not call " + BINDINGS + ".ensureServerRunning", ex);
        }
    }

    /** The lsp.client method, or null if this NetBeans version does not have it. */
    static Method find() {
        try {
            // lsp.client's own loader: the class is in a package the module does not export.
            Class<?> bindings = Class.forName(BINDINGS, false, LanguageServerProvider.class.getClassLoader());
            return bindings.getMethod("ensureServerRunning", Project.class, String.class);
        } catch (ReflectiveOperationException | LinkageError ex) {
            LOG.log(Level.INFO, "lsp.client has no " + BINDINGS + ".ensureServerRunning(Project, String);"
                    + " GDScript editors connect to a background-started server on their next request", ex);
            return null;
        }
    }
}
