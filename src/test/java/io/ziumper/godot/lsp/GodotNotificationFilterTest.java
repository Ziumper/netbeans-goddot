package io.ziumper.godot.lsp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GodotNotificationFilterTest {

    private static final String RESPONSE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{}}}";
    private static final String DIAGNOSTICS
            = "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\",\"params\":{\"diagnostics\":[]}}";

    /** Builds an LSP base-protocol frame; Content-Length counts UTF-8 bytes. */
    private static String frame(String body) {
        return "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] filter(String input) throws IOException {
        try (InputStream in = new GodotNotificationFilter(new ByteArrayInputStream(bytes(input)))) {
            return in.readAllBytes();
        }
    }

    private static String filterToString(String input) throws IOException {
        return new String(filter(input), StandardCharsets.UTF_8);
    }

    @Test
    void passesThroughOrdinaryFrames() throws IOException {
        String input = frame(RESPONSE) + frame(DIAGNOSTICS);
        assertEquals(input, filterToString(input));
    }

    @Test
    void dropsCapabilitiesNotification() throws IOException {
        String capabilities = "{\"jsonrpc\":\"2.0\",\"method\":\"gdscript/capabilities\",\"params\":{\"native_classes\":[]}}";
        String input = frame(RESPONSE) + frame(capabilities) + frame(DIAGNOSTICS);
        assertEquals(frame(RESPONSE) + frame(DIAGNOSTICS), filterToString(input));
    }

    @Test
    void dropsChangeWorkspaceNotificationWrittenWithSpaces() throws IOException {
        String changeWorkspace = "{\"jsonrpc\": \"2.0\", \"method\": \"gdscript_client/changeWorkspace\", \"params\": {}}";
        assertEquals(frame(DIAGNOSTICS), filterToString(frame(changeWorkspace) + frame(DIAGNOSTICS)));
    }

    @Test
    void dropsOnlyWhenEveryFrameIsDropped() throws IOException {
        String capabilities = "{\"jsonrpc\":\"2.0\",\"method\":\"gdscript/capabilities\",\"params\":{}}";
        assertEquals("", filterToString(frame(capabilities) + frame(capabilities)));
    }

    @Test
    void neverDropsRequestsOrResponses() throws IOException {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"gdscript/capabilities\",\"params\":{}}";
        String input = frame(request);
        assertEquals(input, filterToString(input));
    }

    @Test
    void dropsKnownNoisyWindowMessage() throws IOException {
        String noisy = "{\"jsonrpc\":\"2.0\",\"method\":\"window/showMessage\",\"params\":{\"type\":2,"
                + "\"message\":\"The GDScript Language Server might not work correctly with other projects.\"}}";
        String useful = "{\"jsonrpc\":\"2.0\",\"method\":\"window/logMessage\",\"params\":{\"type\":3,\"message\":\"Hello\"}}";
        assertEquals(frame(useful), filterToString(frame(noisy) + frame(useful)));
    }

    @Test
    void handlesMultiByteBodiesAndCaseInsensitiveHeaders() throws IOException {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"zażółć gęślą jaźń\"}";
        String lowerCaseHeader = "content-length: " + bytes(body).length
                + "\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n" + body;
        String input = lowerCaseHeader + frame(DIAGNOSTICS);
        assertArrayEquals(bytes(input), filter(input));
    }

    @Test
    void passesThroughDataThatIsNotAFrame() throws IOException {
        String input = "hello\r\n\r\nworld";
        assertEquals(input, filterToString(input));
    }

    @Test
    void passesThroughTruncatedFrame() throws IOException {
        String input = "Content-Length: 100\r\n\r\n{\"jsonrpc\":";
        assertEquals(input, filterToString(input));
    }

    @Test
    void singleByteReadsMatchBulkReads() throws IOException {
        String capabilities = "{\"jsonrpc\":\"2.0\",\"method\":\"gdscript/capabilities\",\"params\":{}}";
        String input = frame(RESPONSE) + frame(capabilities) + frame(DIAGNOSTICS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new GodotNotificationFilter(new ByteArrayInputStream(bytes(input)))) {
            int c;
            while ((c = in.read()) >= 0) {
                out.write(c);
            }
        }
        assertEquals(frame(RESPONSE) + frame(DIAGNOSTICS), out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void emptyStreamIsEof() throws IOException {
        try (InputStream in = new GodotNotificationFilter(new ByteArrayInputStream(new byte[0]))) {
            assertEquals(-1, in.read());
        }
    }
}
