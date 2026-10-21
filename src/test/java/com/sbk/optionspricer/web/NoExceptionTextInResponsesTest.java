package com.sbk.optionspricer.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An error an HTTP client sees must never carry the text of an exception: that text can quote a URL, a key or a path. Every error response is a fixed
 * message. This reads the server's source and fails if a response is ever built from an exception, so the next edit cannot quietly change that.
 * (What the engine prints is covered separately by the console guard.)
 */
class NoExceptionTextInResponsesTest {

    private static final Path SERVER = Path.of("src/main/java/com/sbk/optionspricer/web/OptionsDashboardServer.java");

    /** The 1-based numbers of the lines that send a response and build it from an exception. */
    static List<Integer> violations(List<String> lines) {
        List<Integer> bad = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean responds = line.contains("sendJsonError(") || line.contains("sendJson(") || line.contains("send(exchange");
            boolean quotesException = line.contains("getMessage()") || line.contains("getLocalizedMessage()")
                    || line.matches(".*\\+\\s*(e|ex|exception|failure|error)\\s*[,;)+].*");
            if (responds && quotesException) {
                bad.add(i + 1);
            }
        }
        return bad;
    }

    @Test
    void noResponseInTheServerIsBuiltFromAnExceptionsMessage() throws IOException {
        List<String> lines = Files.readAllLines(SERVER);
        assertTrue(lines.size() > 500, "the server source was found");

        assertEquals(List.of(), violations(lines));
    }

    @Test
    void theCheckWouldCatchTheMistakeItIsThereFor() {
        List<String> bad = List.of(
                "    sendJsonError(exchange, 500, \"failed: \" + e.getMessage());",
                "    sendJson(exchange, 200, Map.of(\"error\", ex.getLocalizedMessage()));",
                "    send(exchange, 500, \"bad \" + e);",
                "    sendJsonError(exchange, 400, \"fixed message\");",
                "    String message = e.getMessage();   // not a response");

        assertEquals(List.of(1, 2, 3), violations(bad), "the first three build a response from an exception; the last two do not");
    }
}
