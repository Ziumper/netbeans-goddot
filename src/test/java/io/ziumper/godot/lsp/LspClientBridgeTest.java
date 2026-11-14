package io.ziumper.godot.lsp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import org.netbeans.api.project.Project;

class LspClientBridgeTest {

    /** Fails when the lsp.client we build against drops the internal method the bridge calls. */
    @Test
    void findsEnsureServerRunningInTheLspClientWeBuildAgainst() {
        Method m = LspClientBridge.find();

        assertNotNull(m);
        assertTrue(Modifier.isStatic(m.getModifiers()));
        assertArrayEquals(new Class<?>[]{Project.class, String.class}, m.getParameterTypes());
    }
}
