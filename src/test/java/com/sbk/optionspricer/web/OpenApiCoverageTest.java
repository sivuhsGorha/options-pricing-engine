package com.sbk.optionspricer.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The API description is kept by hand, so a route added to the server without being described fails here instead of drifting silently. */
class OpenApiCoverageTest {

    private static final Path SERVER = Path.of("src/main/java/com/sbk/optionspricer/web/OptionsDashboardServer.java");
    private static final Path SPEC = Path.of("docs/openapi.yaml");

    /** Every path the server registers: its contexts and the control sub-routes it switches on. */
    static List<String> routes(String source) {
        List<String> routes = new ArrayList<>();
        Matcher m = Pattern.compile("createContext\\(\"([^\"]+)\"").matcher(source);
        while (m.find()) routes.add(m.group(1));
        Matcher c = Pattern.compile("case \"(/api/control/[a-z-]+)\"").matcher(source);
        while (c.find()) routes.add(c.group(1));
        return routes;
    }

    /** The paths the spec lists: lines of the form "  /path:" under paths. */
    static List<String> documented(String yaml) {
        List<String> paths = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^  (/[^\\s:]*):\\s*$").matcher(yaml);
        while (m.find()) paths.add(m.group(1));
        return paths;
    }

    @Test
    void everyRouteTheServerRegistersIsDescribedInTheSpecAndNothingElseIs() throws IOException {
        List<String> routes = routes(Files.readString(SERVER));
        List<String> documented = documented(Files.readString(SPEC));

        assertTrue(routes.size() >= 15, "the server's routes were found: " + routes);
        List<String> missing = routes.stream().filter(r -> !documented.contains(r)).toList();
        List<String> invented = documented.stream().filter(d -> !routes.contains(d)).toList();

        assertEquals(List.of(), missing, "routes the spec does not describe");
        assertEquals(List.of(), invented, "paths the spec describes that the server does not have");
    }

    @Test
    void theCheckWouldCatchAMissingRoute() {
        assertEquals(List.of("/a", "/api/control/x"), routes("server.createContext(\"/a\", h); case \"/api/control/x\" -> {"));
        assertEquals(List.of("/a", "/b"), documented("paths:\n  /a:\n    get:\n  /b:\n    post:\n"));
    }
}
