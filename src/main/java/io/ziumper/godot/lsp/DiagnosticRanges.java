package io.ziumper.godot.lsp;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.text.Document;
import javax.swing.text.Element;
import org.openide.cookies.EditorCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.URLMapper;

/**
 * Clamps the ranges of {@code textDocument/publishDiagnostics} to the document they are shown in.
 *
 * <p>lsp.client turns a position into an offset as "start of the line + character" and does not check either:
 * a line past the end of the document gives -1, and {@code ErrorDescriptionFactory} then throws
 * {@code IndexOutOfBoundsException: end < start}, which drops every diagnostic of that message. Godot sends such
 * lines on purpose: a range reaching the end of the script ends at {@code (line count, 0)}, one line past the
 * last, which the LSP spec allows. A diagnostic computed for an older, longer version of the text can point past
 * the end too. Every position is therefore clamped the way the spec asks clients to: a line past the end becomes
 * the end of the document, a character past the end of its line becomes the end of that line.
 */
final class DiagnosticRanges {

    private static final Logger LOG = Logger.getLogger(DiagnosticRanges.class.getName());
    static final String METHOD = "textDocument/publishDiagnostics";

    private DiagnosticRanges() {
    }

    /**
     * The message with every diagnostic range clamped to the document, or {@code body} itself if nothing had to
     * change, the file is not open, or the message is not a publishDiagnostics notification.
     *
     * @param lines the length of every line (in chars, without the line break) of the open document of a URI, or
     *        null if the file is not open in an editor (lsp.client then ignores its diagnostics anyway)
     */
    static byte[] clamp(byte[] body, Function<String, int[]> lines) {
        Object message;
        try {
            message = Json.parse(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            LOG.log(Level.FINE, "Not a JSON message; passed on unchanged", ex);
            return body;
        }
        if (!(message instanceof Map<?, ?> m) || !METHOD.equals(m.get("method"))
                || !(m.get("params") instanceof Map<?, ?> params)
                || !(params.get("uri") instanceof String uri)
                || !(params.get("diagnostics") instanceof List<?> diagnostics) || diagnostics.isEmpty()) {
            return body;
        }
        int[] lengths = lines.apply(uri);
        if (lengths == null || lengths.length == 0) {
            return body;
        }
        boolean changed = false;
        for (Object d : diagnostics) {
            if (d instanceof Map<?, ?> diagnostic && diagnostic.get("range") instanceof Map<?, ?> range) {
                changed |= clampRange(range, lengths);
            }
        }
        return changed ? Json.write(message).getBytes(StandardCharsets.UTF_8) : body;
    }

    /** Clamps the diagnostics of a message body against the document open in the IDE; for the stream filter. */
    static byte[] clampToOpenDocument(byte[] body) {
        return clamp(body, DiagnosticRanges::openDocumentLines);
    }

    /**
     * The length of every line of the document open in an editor for the URI, or null if it is not open. Finds
     * the document the way lsp.client does when it shows the diagnostics, and never loads one.
     */
    static int[] openDocumentLines(String uri) {
        FileObject file;
        try {
            file = URLMapper.findFileObject(new URI(uri).toURL());
        } catch (URISyntaxException | MalformedURLException | IllegalArgumentException ex) {
            return null;
        }
        EditorCookie editor = file == null ? null : file.getLookup().lookup(EditorCookie.class);
        Document doc = editor == null ? null : editor.getDocument();
        if (doc == null) {
            return null;
        }
        int[][] lengths = new int[1][];
        doc.render(() -> {
            Element root = doc.getDefaultRootElement();
            int[] l = new int[root.getElementCount()];
            for (int k = 0; k < l.length; k++) {
                Element line = root.getElement(k);
                l[k] = Math.max(0, line.getEndOffset() - line.getStartOffset() - 1); // without the line break
            }
            lengths[0] = l;
        });
        return lengths[0];
    }

    /** Clamps both ends and keeps the end from coming before the start. @return true if anything changed */
    private static boolean clampRange(Map<?, ?> range, int[] lengths) {
        if (!(range.get("start") instanceof Map<?, ?> startObject)
                || !(range.get("end") instanceof Map<?, ?> endObject)) {
            return false;
        }
        int[] start = position(startObject);
        int[] end = position(endObject);
        if (start == null || end == null) {
            return false;
        }
        int[] s = clampPosition(start, lengths);
        int[] e = clampPosition(end, lengths);
        if (e[0] < s[0] || (e[0] == s[0] && e[1] < s[1])) {
            e = s;
        }
        return update(startObject, start, s) | update(endObject, end, e);
    }

    /** {line, character} of a position object, or null if it is not one. */
    private static int[] position(Map<?, ?> p) {
        if (p.get("line") instanceof Json.Num line && p.get("character") instanceof Json.Num character) {
            Integer l = line.intValue();
            Integer c = character.intValue();
            return l == null || c == null ? null : new int[]{l, c};
        }
        return null;
    }

    /** Writes the clamped position into its object, keeping the key order. @return true if it changed */
    private static boolean update(Map<?, ?> object, int[] was, int[] now) {
        if (Arrays.equals(was, now)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) object;
        m.put("line", Json.Num.of(now[0]));
        m.put("character", Json.Num.of(now[1]));
        return true;
    }

    private static int[] clampPosition(int[] p, int[] lengths) {
        int last = lengths.length - 1;
        if (p[0] > last) {
            return new int[]{last, lengths[last]};
        }
        int line = Math.max(0, p[0]);
        return new int[]{line, Math.max(0, Math.min(p[1], lengths[line]))};
    }

    /**
     * Just enough JSON for one message: objects become {@link LinkedHashMap}s (key order is kept), arrays
     * {@link ArrayList}s, numbers {@link Num}s holding their original text, so that whatever is not changed is
     * written back as it came.
     */
    static final class Json {

        private static final int MAX_DEPTH = 256;

        /** A number as written in the message. */
        record Num(String text) {

            static Num of(int value) {
                return new Num(Integer.toString(value));
            }

            /** The value if it is a whole number that fits an int (Godot may write 3 as 3.0), else null. */
            Integer intValue() {
                try {
                    double d = Double.parseDouble(text);
                    return d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE ? (int) d : null;
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
        }

        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        /** @throws IllegalArgumentException if the text is not one JSON value */
        static Object parse(String text) {
            Json p = new Json(text);
            Object v = p.value(0);
            p.skipSpace();
            if (p.i != text.length()) {
                throw p.error("trailing characters");
            }
            return v;
        }

        static String write(Object value) {
            StringBuilder sb = new StringBuilder();
            write(value, sb);
            return sb.toString();
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nested too deeply");
            }
            skipSpace();
            if (i >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw error("unexpected '" + c + "'");
            }
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            skipSpace();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                skipSpace();
                if (peek() != '"') {
                    throw error("expected a key");
                }
                String key = string();
                skipSpace();
                expect(':');
                m.put(key, value(depth + 1));
                skipSpace();
                char c = next();
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> array(int depth) {
            List<Object> a = new ArrayList<>();
            i++; // [
            skipSpace();
            if (peek() == ']') {
                i++;
                return a;
            }
            while (true) {
                a.add(value(depth + 1));
                skipSpace();
                char c = next();
                if (c == ']') {
                    return a;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String string() {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = next();
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw error("short \\u escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("bad \\u escape");
                        }
                        i += 4;
                    }
                    default -> throw error("bad escape");
                }
            }
        }

        private Num number() {
            int start = i;
            if (peek() == '-') {
                i++;
            }
            while (i < s.length() && "0123456789+-.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String text = s.substring(start, i);
            try {
                Double.parseDouble(text);
            } catch (NumberFormatException ex) {
                throw error("bad number " + text);
            }
            return new Num(text);
        }

        private Object literal(String word, Object v) {
            if (!s.startsWith(word, i)) {
                throw error("unexpected token");
            }
            i += word.length();
            return v;
        }

        private void skipSpace() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                i++;
            }
        }

        private char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        private char next() {
            if (i >= s.length()) {
                throw error("unexpected end");
            }
            return s.charAt(i++);
        }

        private void expect(char c) {
            if (next() != c) {
                throw error("expected '" + c + "'");
            }
        }

        private IllegalArgumentException error(String what) {
            return new IllegalArgumentException(what + " at " + i);
        }

        private static void write(Object v, StringBuilder sb) {
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Map<?, ?> m) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeString(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    write(e.getValue(), sb);
                }
                sb.append('}');
            } else if (v instanceof List<?> a) {
                sb.append('[');
                for (int k = 0; k < a.size(); k++) {
                    if (k > 0) {
                        sb.append(',');
                    }
                    write(a.get(k), sb);
                }
                sb.append(']');
            } else if (v instanceof String str) {
                writeString(str, sb);
            } else if (v instanceof Num n) {
                sb.append(n.text());
            } else {
                sb.append(v); // Boolean
            }
        }

        private static void writeString(String str, StringBuilder sb) {
            sb.append('"');
            for (int k = 0; k < str.length(); k++) {
                char c = str.charAt(k);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
        }
    }
}
