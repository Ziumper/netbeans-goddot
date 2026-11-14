package io.ziumper.godot.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class DiagnosticRangesTest {

    private static final String URI = "file:///C:/game/player.gd";
    /** "extends Node\n\nfunc _ready():\n\tpass\n": four lines and the empty one after the last line break. */
    private static final int[] LINES = {12, 0, 14, 5, 0};
    private static final Function<String, int[]> OPEN = uri -> URI.equals(uri) ? LINES : null;

    private static String message(String... ranges) {
        StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\","
                + "\"params\":{\"diagnostics\":[");
        for (int k = 0; k < ranges.length; k++) {
            sb.append(k > 0 ? "," : "").append("{\"code\":2,\"message\":\"m").append(k).append("\",\"range\":")
                    .append(ranges[k]).append(",\"severity\":2,\"source\":\"gdscript\"}");
        }
        return sb.append("],\"uri\":\"").append(URI).append("\"}}").toString();
    }

    private static String range(int startLine, int startChar, int endLine, int endChar) {
        return "{\"end\":{\"character\":" + endChar + ",\"line\":" + endLine + "},"
                + "\"start\":{\"character\":" + startChar + ",\"line\":" + startLine + "}}";
    }

    private static String clamp(String body, Function<String, int[]> lines) {
        return new String(DiagnosticRanges.clamp(body.getBytes(StandardCharsets.UTF_8), lines), StandardCharsets.UTF_8);
    }

    /** Godot ends a range that reaches the end of the script at (line count, 0); lsp.client made that offset -1. */
    @Test
    void endOneLinePastTheLastBecomesTheEndOfTheDocument() {
        assertEquals(message(range(2, 0, 4, 0)), clamp(message(range(2, 0, 5, 0)), OPEN));
    }

    @Test
    void positionsOfAnOlderLongerTextAreClampedToTheDocument() {
        assertEquals(message(range(4, 0, 4, 0)), clamp(message(range(9, 1, 12, 3)), OPEN));
    }

    @Test
    void characterPastTheEndOfItsLineBecomesTheEndOfThatLine() {
        assertEquals(message(range(3, 1, 3, 5)), clamp(message(range(3, 1, 3, 40)), OPEN));
    }

    @Test
    void negativePositionsBecomeTheStartOfTheDocument() {
        assertEquals(message(range(0, 0, 0, 4)), clamp(message(range(-1, -3, 0, 4)), OPEN));
    }

    @Test
    void endNeverComesBeforeTheStart() {
        assertEquals(message(range(3, 5, 3, 5)), clamp(message(range(3, 40, 3, 2)), OPEN));
    }

    @Test
    void onlyTheBrokenRangesChangeAndEverythingElseIsKept() {
        String good = range(0, 0, 0, 12);
        assertEquals(message(good, range(3, 1, 4, 0)), clamp(message(good, range(3, 1, 5, 0)), OPEN));
    }

    @Test
    void validRangesKeepTheMessageAsItCame() {
        byte[] body = message(range(0, 0, 0, 12), range(3, 1, 4, 0)).getBytes(StandardCharsets.UTF_8);
        assertSame(body, DiagnosticRanges.clamp(body, OPEN));
    }

    @Test
    void fileNotOpenInAnEditorIsLeftAlone() {
        byte[] body = message(range(2, 0, 5, 0)).getBytes(StandardCharsets.UTF_8);
        assertSame(body, DiagnosticRanges.clamp(body, uri -> null));
    }

    @Test
    void otherMessagesAndBrokenJsonAreLeftAlone() {
        for (String text : new String[]{
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"range\":" + range(9, 0, 9, 0) + "}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\",\"params\":{\"diagnostics\":[]"
                    + ",\"uri\":\"" + URI + "\"}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\",\"params\":{\"diag",
            "not json at all"}) {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            assertSame(body, DiagnosticRanges.clamp(body, OPEN), text);
        }
    }

    @Test
    void godotStyleSpacingAndFloatsAreUnderstood() {
        String spaced = "{\"jsonrpc\": \"2.0\", \"method\": \"textDocument/publishDiagnostics\", \"params\": {"
                + "\"diagnostics\": [{\"message\": \"zażółć \\\"x\\\"\\n\\u0001\", \"range\": {"
                + "\"end\": {\"character\": 0.0, \"line\": 5.0}, \"start\": {\"character\": 2, \"line\": 2}},"
                + " \"severity\": 1.0, \"related\": [true, false, null, -1.5e3]}], \"uri\": \"" + URI + "\"}}";

        assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\",\"params\":{"
                + "\"diagnostics\":[{\"message\":\"zażółć \\\"x\\\"\\n\\u0001\",\"range\":{"
                + "\"end\":{\"character\":0,\"line\":4},\"start\":{\"character\":2,\"line\":2}},"
                + "\"severity\":1.0,\"related\":[true,false,null,-1.5e3]}],\"uri\":\"" + URI + "\"}}",
                clamp(spaced, OPEN));
    }

    @Test
    void jsonRoundTripKeepsEscapesAndSurrogatePairs() {
        String text = "{\"a\":\"tab\\tquote\\\"slash\\\\ \\ud83c\\udfae \\/\",\"b\":[],\"c\":{}}";
        Object parsed = DiagnosticRanges.Json.parse(text);
        assertEquals("{\"a\":\"tab\\tquote\\\"slash\\\\ \uD83C\uDFAE /\",\"b\":[],\"c\":{}}",
                DiagnosticRanges.Json.write(parsed));
    }

    @Test
    void jsonRejectsWhatIsNotOneValue() {
        for (String text : new String[]{"", "{", "{\"a\":1,}", "[1 2]", "\"open", "{} {}", "tru", "01x"}) {
            assertThrows(IllegalArgumentException.class, () -> DiagnosticRanges.Json.parse(text), text);
        }
    }

    @Test
    void jsonRejectsVeryDeepNestingInsteadOfOverflowingTheStack() {
        String deep = "[".repeat(10_000) + "]".repeat(10_000);
        assertThrows(IllegalArgumentException.class, () -> DiagnosticRanges.Json.parse(deep));
    }
}
