package io.ziumper.godot.lsp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Server-to-client stream filter that drops Godot-specific notifications the NetBeans LSP client
 * does not understand. NetBeans would otherwise log each of them as a WARNING, and
 * {@code gdscript/capabilities} alone is ~100 KB.
 *
 * <p>The stream is parsed as LSP base-protocol frames ({@code Content-Length} header, blank line, body).
 * Frames are handled as raw bytes, so there is no charset issue. Anything that does not look like a
 * frame is passed through untouched.
 */
final class GodotNotificationFilter extends InputStream {

    /** Notification methods that are swallowed. */
    private static final List<String> DROPPED_METHODS = List.of("gdscript/capabilities", "gdscript_client/changeWorkspace");
    /** Godot's window messages (showMessage/logMessage) containing one of these texts are swallowed. */
    private static final List<String> DROPPED_WINDOW_MESSAGES = List.of(
            "might not work correctly with other project");
    /** A notification carries its "method" key before the (potentially huge) params; only this prefix is inspected. */
    private static final int SNIFF_LIMIT = 256;
    private static final int MAX_HEADER = 4096;
    private static final byte[] SEPARATOR = {'\r', '\n', '\r', '\n'};

    private final InputStream in;
    private byte[] pending = new byte[0];
    private int pos;

    GodotNotificationFilter(InputStream in) {
        this.in = in;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : (one[0] & 0xFF);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        while (pos >= pending.length) {
            if (!nextFrame()) {
                return -1;
            }
        }
        int n = Math.min(len, pending.length - pos);
        System.arraycopy(pending, pos, b, off, n);
        pos += n;
        return n;
    }

    @Override
    public int available() {
        return pending.length - pos;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /** Reads the next frame into {@link #pending}, skipping dropped ones. @return false on EOF. */
    private boolean nextFrame() throws IOException {
        while (true) {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < SEPARATOR.length) {
                int c = in.read();
                if (c < 0) {
                    // EOF: hand over whatever was read so the consumer sees the truncated data
                    return setPending(header.toByteArray());
                }
                header.write(c);
                matched = (c == SEPARATOR[matched]) ? matched + 1 : (c == SEPARATOR[0] ? 1 : 0);
                if (header.size() > MAX_HEADER && matched == 0 && contentLength(header.toByteArray()) < 0) {
                    return setPending(header.toByteArray()); // not a frame, pass through
                }
            }
            byte[] head = header.toByteArray();
            int length = contentLength(head);
            if (length < 0) {
                return setPending(head);
            }
            byte[] body = in.readNBytes(length);
            if (body.length < length) {
                return setPending(concat(head, body));
            }
            if (isDropped(body)) {
                continue;
            }
            return setPending(concat(head, body));
        }
    }

    private boolean setPending(byte[] data) {
        pending = data;
        pos = 0;
        return data.length > 0;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static int contentLength(byte[] header) {
        String h = new String(header, StandardCharsets.ISO_8859_1);
        for (String line : h.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    return Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException ex) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static boolean isDropped(byte[] body) {
        String head = new String(body, 0, Math.min(body.length, SNIFF_LIMIT), StandardCharsets.UTF_8);
        if (head.contains("\"id\"")) {
            return false; // a request or response, never drop
        }
        if (head.contains("\"method\":\"window/") || head.contains("\"method\": \"window/")) {
            String text = new String(body, StandardCharsets.UTF_8); // window messages are short
            for (String message : DROPPED_WINDOW_MESSAGES) {
                if (text.contains(message)) {
                    return true;
                }
            }
        }
        for (String method : DROPPED_METHODS) {
            if (head.contains("\"method\":\"" + method + "\"") || head.contains("\"method\": \"" + method + "\"")) {
                return true;
            }
        }
        return false;
    }
}
