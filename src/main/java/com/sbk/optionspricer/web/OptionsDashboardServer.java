package com.sbk.optionspricer.web;

import com.sbk.optionspricer.execution.PositionTracker;
import com.sbk.optionspricer.volatility.SabrModel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import com.sbk.optionspricer.execution.OrderManager;
import com.sbk.optionspricer.market.MarketSnapshotAdapter;
import com.sbk.optionspricer.market.MarketSnapshot;

public class OptionsDashboardServer {

    static {
        // JDK HttpServer limits, read when the server class initialises. A request that never
        // completes is closed instead of holding a connection open indefinitely.
        String requestTimeout = com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("HTTP_REQUEST_TIMEOUT_SECONDS", "30");
        setPropertyIfAbsent("sun.net.httpserver.maxReqTime", requestTimeout);
        setPropertyIfAbsent("sun.net.httpserver.maxRspTime", "60");
        // The JDK evaluates these timeouts only once per tick (default 10s), so a short timeout fires late.
        setPropertyIfAbsent("sun.net.httpserver.clockTick", "1000");
        setPropertyIfAbsent("sun.net.httpserver.idleInterval", "30");
        setPropertyIfAbsent("sun.net.httpserver.maxIdleConnections", "100");
        setPropertyIfAbsent("sun.net.httpserver.maxReqHeaders", "50");
    }

    private static void setPropertyIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    /**
     * Network-facing security settings.
     *
     * @param trustedProxies IPs of reverse proxies whose X-Forwarded-* headers are believed
     * @param cookieSecure   always mark session cookies Secure and send HSTS (set when serving over TLS)
     */
    public record SecurityOptions(java.util.Set<String> trustedProxies, boolean cookieSecure) {
        public SecurityOptions {
            trustedProxies = java.util.Set.copyOf(trustedProxies);
        }

        public static SecurityOptions fromEnvironment() {
            return new SecurityOptions(
                    ClientAddressResolver.parseTrustedProxies(
                            com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("TRUSTED_PROXIES", "")),
                    Boolean.parseBoolean(com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("COOKIE_SECURE", "false")));
        }
    }

    /** Bounded handler pool: a request flood is queued up to a limit and then shed, never unbounded. */
    static java.util.concurrent.ThreadPoolExecutor createHttpExecutor() {
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
        return new java.util.concurrent.ThreadPoolExecutor(8, 32, 60, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(256),
                runnable -> {
                    Thread thread = new Thread(runnable, "http-handler-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }
    public static volatile int publicServerPort = 8080;

    private final String apiSecret;
    private final BrowserSessionManager sessions;
    private final java.util.Set<String> trustedProxies;
    private final SecurityOptions securityOptions;
    private final MmapStateReader mmapReader;
    private final HttpServer server;
    private final ExecutorService httpExecutor;
    private final WebSocketDashboardServer wsServer;
    
    private final OrderManager orderManager;
    private final PositionTracker positionTracker;
    private final MarketSnapshotAdapter marketAdapter;
    /** Fitted surface provider; null means the endpoint serves the fixed-parameter demonstration surface. */
    private volatile com.sbk.optionspricer.volatility.VolatilitySurfaceSource surfaceSource;

    /** Supplies the fitted volatility surface. Call before {@link #start()}; without it the surface is a labelled demo. */
    public void setSurfaceSource(com.sbk.optionspricer.volatility.VolatilitySurfaceSource source) {
        this.surfaceSource = source;
    }

    private volatile com.sbk.optionspricer.execution.OperatorControls operatorControls;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_IN = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Exposes halt/resume and the strategy switch at /api/control. Call before {@link #start()}. */
    public void setOperatorControls(com.sbk.optionspricer.execution.OperatorControls controls) {
        this.operatorControls = controls;
    }

    private static java.util.Map<String, Object> controlJson(com.sbk.optionspricer.execution.OperatorControls.ControlState s) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("halted", s.halted());
        body.put("haltReason", s.haltReason());
        body.put("haltedAt", s.haltedAt() == null ? null : s.haltedAt().toEpochMilli());
        body.put("strategyEnabled", s.strategyEnabled());
        body.put("symbol", s.symbol());
        body.put("triggerPct", Double.isFinite(s.triggerPct()) ? s.triggerPct() : null);
        body.put("baseQuantity", s.baseQuantity());
        return body;
    }

    /** State-changing calls: POST only, and a browser (cookie) request must carry an allowed Origin, against CSRF. */
    private boolean acceptMutation(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendJsonError(exchange, 405, "method not allowed");
            return false;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        boolean browser = BrowserSessionManager.getSessionToken(exchange.getRequestHeaders().getFirst("Cookie")) != null;
        if (browser && (origin == null || !sessions.isAllowedOrigin(origin))) {
            sendJsonError(exchange, 403, "origin not allowed");
            return false;
        }
        return true;
    }

    /** A small JSON object body, or an empty object for no body. Sends the error and returns null when the body is unusable. */
    private static com.fasterxml.jackson.databind.JsonNode readJsonObject(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(4097);
        if (body.length > 4096) {
            sendJsonError(exchange, 413, "payload too large");
            return null;
        }
        if (body.length == 0) {
            return JSON_IN.createObjectNode();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = JSON_IN.readTree(body);
            if (node == null || !node.isObject()) {
                sendJsonError(exchange, 400, "body must be a JSON object");
                return null;
            }
            return node;
        } catch (IOException malformed) {
            sendJsonError(exchange, 400, "body must be a JSON object");
            return null;
        }
    }

    public OptionsDashboardServer(String apiSecret, String operatorPassword, String allowedOrigins,
                                  String bindAddress, int port, int wsPort, MmapStateReader mmapReader,
                                  String webRoot, OrderManager orderManager, PositionTracker positionTracker,
                                  MarketSnapshotAdapter marketAdapter) throws IOException {
        this(apiSecret, operatorPassword, allowedOrigins, bindAddress, port, wsPort, mmapReader, webRoot,
                orderManager, positionTracker, marketAdapter, SecurityOptions.fromEnvironment());
    }

    public OptionsDashboardServer(String apiSecret, String operatorPassword, String allowedOrigins,
                                  String bindAddress, int port, int wsPort, MmapStateReader mmapReader,
                                  String webRoot, OrderManager orderManager, PositionTracker positionTracker,
                                  MarketSnapshotAdapter marketAdapter, SecurityOptions securityOptions) throws IOException {
        this.securityOptions = securityOptions;
        try {
            validateApiSecret(apiSecret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        this.apiSecret = apiSecret;
        this.sessions = new BrowserSessionManager(operatorPassword, allowedOrigins);
        this.trustedProxies = securityOptions.trustedProxies();
        this.mmapReader = mmapReader;
        this.orderManager = orderManager;
        this.positionTracker = positionTracker;
        this.marketAdapter = marketAdapter;
        InetAddress address = InetAddress.getByName(bindAddress);
        this.server = HttpServer.create(new InetSocketAddress(address, port), 128);
        this.httpExecutor = createHttpExecutor();
        this.server.setExecutor(httpExecutor);
        this.wsServer = new WebSocketDashboardServer(bindAddress, wsPort, mmapReader, apiSecret, sessions, trustedProxies);
        configureContexts(webRoot);
    }

    public static void validateApiSecret(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("API_SECRET must be configured and at least 32 characters long.");
        }
    }

    private static double sanitizeRiskValue(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    public static String requireEnvironmentVariable(String name) {
        String value = com.sbk.optionspricer.config.EnvironmentConfigLoader.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("FATAL: " + name + " is missing or empty (set it in the environment or .env).");
        }
        return value;
    }

    public static String healthSnapshot(MarketSnapshotAdapter adapter) {
        String symbol = "SPY";
        String sourceStatus = "UNAVAILABLE";
        long lastUpdate = 0L;
        long quoteAge = 0L;
        boolean tradable = false;

        if (adapter != null) {
            MarketSnapshot quote = adapter.getSnapshot(symbol);
            if (quote != null) {
                sourceStatus = quote.status().name();
                lastUpdate = quote.sourceTimestamp().toEpochMilli();
                quoteAge = quote.derivedQuoteAgeMs();
                tradable = quote.status() == com.sbk.optionspricer.market.MarketDataStatus.LIVE && quoteAge <= 30000L;
            }
        }

        if (lastUpdate <= 0L) {
            lastUpdate = System.currentTimeMillis();
        }

        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("symbol", symbol);
        body.put("sourceStatus", sourceStatus);
        body.put("lastUpdate", lastUpdate);
        body.put("quoteAgeMs", quoteAge);
        body.put("tradable", tradable);
        return Json.write(body);
    }

    public void start() {
        Thread wsThread = new Thread(wsServer, "ws-server-thread");
        wsThread.setDaemon(true);
        wsThread.start();
        server.start();
        publicServerPort = server.getAddress().getPort();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    public int getWebSocketPort() {
        return wsServer.getPort();
    }

    public void stop() {
        server.stop(0);
        httpExecutor.shutdownNow();
        wsServer.stop();
        if (mmapReader != null) mmapReader.close();
    }

    /** The fitted surface with its provenance, the calibration status while none is ready, or the labelled demo. */
    java.util.Map<String, Object> surfaceBody(String model) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        com.sbk.optionspricer.volatility.VolatilitySurfaceSource source = surfaceSource;
        if (source == null) {
            return demoSurface(body, model);
        }
        var snapshot = source.latest();
        var status = source.status();
        var fit = snapshot.map(s -> "SSVI".equals(model) ? s.ssvi() : s.sabr()).orElse(null);
        if (snapshot.isEmpty() || fit == null) {
            body.put("ready", false);
            body.put("status", snapshot.isEmpty() ? status.state().name() : "FAILED");
            body.put("message", snapshot.isEmpty() ? status.message()
                    : model + " could not be fitted: " + String.join("; ", snapshot.get().warnings()));
            body.put("updatedAt", status.updatedAt().toEpochMilli());
            return body;
        }
        var s = snapshot.get();
        body.put("ready", true);
        body.put("source", s.source());
        body.put("demo", !s.marketData());
        body.put("symbol", s.symbol());
        body.put("spot", Json.round(s.spot(), 2));
        body.put("asOf", s.asOf().toEpochMilli());
        body.put("quotesUsed", fit.quotesUsed());
        body.put("quotesSkipped", s.quotesSkipped());
        body.put("rmse", Json.round(fit.rmse(), 6));
        body.put("noArbitrageConditionsHold", fit.noArbitrageConditionsHold());
        java.util.Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        fit.parameters().forEach((k, v) -> parameters.put(k, Json.round(v, 6)));
        body.put("parameters", parameters);
        body.put("warnings", fit.warnings());
        double[] x = new double[fit.strikes().length];
        for (int j = 0; j < x.length; j++) x[j] = Json.round(fit.strikes()[j], 2);
        double[] y = new double[fit.expiries().length];
        for (int i = 0; i < y.length; i++) y[i] = Json.round(fit.expiries()[i], 4);
        double[][] z = new double[y.length][x.length];
        for (int i = 0; i < y.length; i++) for (int j = 0; j < x.length; j++) z[i][j] = Json.round(fit.vols()[i][j], 5);
        body.put("x", x);
        body.put("y", y);
        body.put("z", z);
        java.util.List<java.util.Map<String, Object>> points = new java.util.ArrayList<>(fit.points().size());
        for (var p : fit.points()) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("t", Json.round(p.timeToExpiry(), 4));
            row.put("strike", Json.round(p.strike(), 2));
            row.put("marketVol", Json.round(p.marketVol(), 5));
            row.put("modelVol", Json.round(p.modelVol(), 5));
            points.add(row);
        }
        body.put("points", points);
        return body;
    }

    /** Fixed-parameter surface at spot 100, used only when no calibration service is wired in. Labelled DEMO. */
    private static java.util.Map<String, Object> demoSurface(java.util.Map<String, Object> body, String model) {
        int[] strikes = {50, 65, 80, 90, 100, 110, 120, 135, 150};
        double[] expiries = {0.1, 0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0};
        com.sbk.optionspricer.volatility.SsviApproximation.SsviParams ssviParams =
                new com.sbk.optionspricer.volatility.SsviApproximation.SsviParams(0.55, 0.25, -0.50);
        double[][] z = new double[expiries.length][strikes.length];
        for (int i = 0; i < expiries.length; i++) {
            for (int j = 0; j < strikes.length; j++) {
                double vol = "SSVI".equals(model)
                        ? com.sbk.optionspricer.volatility.SsviApproximation.impliedVol(100.0, strikes[j], expiries[i], 0.22, ssviParams)
                        : SabrModel.impliedVolatility(100.0, strikes[j], expiries[i], 0.35, 0.5, -0.75, 0.85);
                z[i][j] = Json.round(vol, 4);
            }
        }
        double[] y = new double[expiries.length];
        for (int i = 0; i < expiries.length; i++) y[i] = Json.round(expiries[i], 2);
        body.put("ready", true);
        body.put("source", "DEMO");
        body.put("demo", true);
        body.put("message", "fixed demonstration parameters; no calibration service is configured");
        body.put("x", strikes);
        body.put("y", y);
        body.put("z", z);
        body.put("points", java.util.List.of());
        return body;
    }

    private void configureContexts(String webRoot) {
        server.createContext("/login", guarded(this::handleLogin));
        server.createContext("/logout", guarded(this::handleLogout));
        server.createContext("/api/spot", guarded(exchange -> {
            applySecurityHeaders(exchange, true, isSecureRequest(exchange));
            if (!authorizeApi(exchange)) return;
            if (marketAdapter == null) {
                sendJsonError(exchange, 503, "market data unavailable");
                return;
            }
            MarketSnapshot snapshot = marketAdapter.getSnapshot("SPY");
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("symbol", snapshot.symbol());
            // An unavailable source has no price; the snapshot's placeholder must not be shown as the spot.
            boolean unavailable = snapshot.status() == com.sbk.optionspricer.market.MarketDataStatus.UNAVAILABLE;
            body.put("spotPrice", unavailable ? null : Json.round(snapshot.last(), 2));
            // A field the provider did not supply is null, never a placeholder number.
            body.put("bid", !unavailable && snapshot.hasBook() ? Json.round(snapshot.bid(), 2) : null);
            body.put("ask", !unavailable && snapshot.hasBook() ? Json.round(snapshot.ask(), 2) : null);
            body.put("volume", !unavailable && snapshot.hasVolume() ? snapshot.volume() : null);
            body.put("source", snapshot.source());
            body.put("timestamp", snapshot.timestamp().toEpochMilli());
            body.put("status", snapshot.status().name());
            sendJson(exchange, 200, body);
        }));

        server.createContext("/api/health", guarded(exchange -> {
            applySecurityHeaders(exchange, true, isSecureRequest(exchange));
            if (!authorizeApi(exchange)) return;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            send(exchange, 200, healthSnapshot(marketAdapter));
        }));

        server.createContext("/api/execution", guarded(exchange -> {
            applySecurityHeaders(exchange, true, isSecureRequest(exchange));
            if (!authorizeApi(exchange)) return;
            java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
            if (orderManager != null) {
                for (var audit : orderManager.getAuditTrail()) {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("orderId", audit.orderId());
                    row.put("accepted", audit.accepted());
                    row.put("sourceStatus", audit.sourceStatus().name());
                    row.put("rejectionReason", audit.rejectionReason());
                    row.put("fillPrice", Json.round(audit.fillPrice(), 2));
                    row.put("quantity", audit.quantity());
                    row.put("timestamp", audit.timestamp().toEpochMilli());
                    rows.add(row);
                }
            }
            sendJson(exchange, 200, rows);
        }));

        server.createContext("/api/positions", guarded(exchange -> {
            applySecurityHeaders(exchange, true, isSecureRequest(exchange));
            if (!authorizeApi(exchange)) return;
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            var halt = orderManager == null ? null : orderManager.getTradingHalt();
            body.put("halted", halt != null && halt.isHalted());
            body.put("haltReason", halt == null ? null : halt.reason().map(r -> r.message()).orElse(null));
            java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
            if (positionTracker != null) {
                for (var entry : positionTracker.getPositions().entrySet()) {
                    var position = entry.getValue();
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("symbol", entry.getKey());
                    row.put("quantity", position.getQuantity());
                    row.put("multiplier", position.getMultiplier());
                    row.put("notional", Json.round(positionTracker.getNotional(entry.getKey()), 2));
                    rows.add(row);
                }
            }
            body.put("positions", rows);
            sendJson(exchange, 200, body);
        }));

        server.createContext("/api/control", guarded(exchange -> {
            applySecurityHeaders(exchange, true, isSecureRequest(exchange));
            if (!authorizeApi(exchange)) return;
            com.sbk.optionspricer.execution.OperatorControls controls = operatorControls;
            if (controls == null) {
                sendJsonError(exchange, 503, "operator controls unavailable");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/api/control".equals(path)) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    sendJsonError(exchange, 405, "method not allowed");
                    return;
                }
                sendJson(exchange, 200, controlJson(controls.state()));
                return;
            }
            if (!acceptMutation(exchange)) return;
            com.fasterxml.jackson.databind.JsonNode body = readJsonObject(exchange);
            if (body == null) return;
            switch (path) {
                case "/api/control/halt" -> {
                    com.fasterxml.jackson.databind.JsonNode reason = body.get("reason");
                    sendJson(exchange, 200, controlJson(controls.halt(reason == null || !reason.isTextual() ? null : reason.asText())));
                }
                case "/api/control/resume" -> sendJson(exchange, 200, controlJson(controls.resume()));
                case "/api/control/strategy" -> {
                    com.fasterxml.jackson.databind.JsonNode enabled = body.get("enabled");
                    if (enabled == null || !enabled.isBoolean()) {
                        sendJsonError(exchange, 400, "body must be {\"enabled\": true|false}");
                        return;
                    }
                    sendJson(exchange, 200, controlJson(controls.setStrategyEnabled(enabled.asBoolean())));
                }
                default -> sendJsonError(exchange, 404, "not found");
            }
        }));

        server.createContext("/api/risk", guarded(exchange -> {
            applySecurityHeaders(exchange, true, isSecureRequest(exchange));
            if (!authorizeApi(exchange)) return;
            MmapStateReader.RiskState state;
            try {
                state = mmapReader.readState();
            } catch (IllegalStateException e) {
                // Reporting "zero risk" here would tell monitoring the book is flat while the engine is down.
                sendJsonError(exchange, 503, "risk state unavailable");
                return;
            }
            PositionTracker.PortfolioExposure trackedExposure = positionTracker == null
                    ? new PositionTracker.PortfolioExposure(0.0, 0.0, 0.0, 0.0)
                    : positionTracker.snapshotExposure();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            send(exchange, 200, riskJson(state, trackedExposure));
        }));

        server.createContext("/api/surface3d", guarded(exchange -> {
            applySecurityHeaders(exchange, true, isSecureRequest(exchange));
            if (!authorizeApi(exchange)) return;
            String requested = queryParam(exchange.getRequestURI().getRawQuery(), "model");
            // FREE_SABR was a deprecated alias of SABR (no free-boundary correction was ever implemented).
            String model = "SSVI".equals(requested) ? "SSVI" : "SABR";
            sendJson(exchange, 200, surfaceBody(model));
        }));
        server.createContext("/", new StaticFileHandler(webRoot, this::isSecureRequest));
    }

    /** Convenience for callers that cannot tell whether the request arrived over TLS (no HSTS is sent). */
    public static void applySecurityHeaders(HttpExchange exchange, boolean isApi) {
        applySecurityHeaders(exchange, isApi, false);
    }

    public static void applySecurityHeaders(HttpExchange exchange, boolean isApi, boolean secure) {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Security-Policy",
                "default-src 'self'; " +
                "script-src 'self' https://cdn.plot.ly; " +
                "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
                "font-src 'self' https://fonts.gstatic.com; " +
                "connect-src 'self'; " +
                "img-src 'self' data:; " +
                "object-src 'none'; " +
                "frame-ancestors 'none'; " +
                "base-uri 'self'; " +
                "form-action 'self'");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
        headers.set("Permissions-Policy", "geolocation=(), camera=(), microphone=(), payment=()");
        if (secure) {
            headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        if (isApi) {
            headers.set("Cache-Control", "no-store, no-cache, must-revalidate");
            headers.set("Pragma", "no-cache");
        }
    }

    /**
     * True when the connection should be treated as TLS: either COOKIE_SECURE is set, or a configured
     * trusted proxy reports https. X-Forwarded-Proto from any other peer is ignored, so a client cannot
     * forge it.
     */
    boolean isSecureRequest(HttpExchange exchange) {
        if (securityOptions.cookieSecure()) {
            return true;
        }
        String proto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
        if (proto == null || !"https".equalsIgnoreCase(proto.trim())) {
            return false;
        }
        InetSocketAddress peer = exchange.getRemoteAddress();
        return peer != null && peer.getAddress() != null && trustedProxies.contains(peer.getAddress().getHostAddress());
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        applySecurityHeaders(exchange, true, isSecureRequest(exchange));
        if (!"/login".equals(exchange.getRequestURI().getPath())) {
            send(exchange, 404, "Not Found");
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            send(exchange, 405, "Method Not Allowed");
            return;
        }
        if (!sessions.isAllowedOrigin(exchange.getRequestHeaders().getFirst("Origin"))) {
            send(exchange, 403, "Forbidden");
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(4097);
        if (body.length > 4096) {
            send(exchange, 413, "Payload Too Large");
            return;
        }
        String clientKey = ClientAddressResolver.resolve(exchange.getRemoteAddress(),
                exchange.getRequestHeaders().getFirst("X-Forwarded-For"), trustedProxies);
        BrowserSessionManager.LoginResult result = sessions.login(
                new String(body, StandardCharsets.UTF_8), clientKey);
        if (result.rateLimited()) {
            exchange.getResponseHeaders().set("Retry-After", "60");
            send(exchange, 429, "Too Many Requests");
        } else if (result.sessionToken() == null) {
            send(exchange, 401, "Unauthorized");
        } else {
            boolean secure = isSecureRequest(exchange);
            String cookieHeader = BrowserSessionManager.COOKIE_NAME + "=" +
                    result.sessionToken() + "; Path=/; Max-Age=" + BrowserSessionManager.SESSION_MAX_AGE_SECONDS +
                    "; HttpOnly; SameSite=Strict" + (secure ? "; Secure" : "");
            exchange.getResponseHeaders().set("Set-Cookie", cookieHeader);
            send(exchange, 200, "{\"authenticated\":true}");
        }
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        applySecurityHeaders(exchange, true, isSecureRequest(exchange));
        if (!"/logout".equals(exchange.getRequestURI().getPath())) {
            send(exchange, 404, "Not Found");
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            send(exchange, 405, "Method Not Allowed");
            return;
        }
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        sessions.logout(cookie);
        boolean secure = isSecureRequest(exchange);
        String cookieHeader = BrowserSessionManager.COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" + (secure ? "; Secure" : "");
        exchange.getResponseHeaders().set("Set-Cookie", cookieHeader);
        send(exchange, 200, "{\"authenticated\":false}");
    }

    /**
     * Builds the /api/risk body. Values the platform does not compute (optimizer margin, L3 fill
     * probability, router allocation) are null rather than invented numbers.
     */
    public static String riskJson(MmapStateReader.RiskState state, PositionTracker.PortfolioExposure trackedExposure) {
        double netDelta = sanitizeRiskValue(state.netDelta, 0.0);
        double netGamma = sanitizeRiskValue(state.netGamma, 0.0);
        double netVega = sanitizeRiskValue(state.netVega, 0.0);
        double scenarioMargin = sanitizeRiskValue(state.scenarioMargin, 0.0);
        int hedgeQty = (int) Math.round(-netDelta);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("netDelta", Json.round(netDelta, 2));
        body.put("netGamma", Json.round(netGamma, 2));
        body.put("netVega", Json.round(netVega, 2));
        body.put("scenarioMargin", Double.isFinite(state.scenarioMargin) ? Json.round(scenarioMargin, 2) : null);
        body.put("recommendedHedge", hedgeQty);
        body.put("optimizedMargin", null);
        body.put("marginReductionPct", null);
        body.put("trackedNetDelta", Json.round(sanitizeRiskValue(trackedExposure.netDelta(), 0.0), 2));
        body.put("trackedNetGamma", Json.round(sanitizeRiskValue(trackedExposure.netGamma(), 0.0), 2));
        body.put("trackedNetVega", Json.round(sanitizeRiskValue(trackedExposure.netVega(), 0.0), 2));
        body.put("trackedNotional", Json.round(sanitizeRiskValue(trackedExposure.netNotional(), 0.0), 2));
        body.put("l3FillProb", null);
        body.put("sorAllocations", null);
        return Json.write(body);
    }

    /** Returns the decoded value of a query parameter (exact name match), or null. */
    static String queryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                if (name.equals(java.net.URLDecoder.decode(key, StandardCharsets.UTF_8))) {
                    return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
                }
            } catch (IllegalArgumentException ignored) {
                // malformed percent-encoding: treat as not present
            }
        }
        return null;
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        send(exchange, status, Json.write(body));
    }

    private static void sendJsonError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, java.util.Map.of("error", message));
    }

    /**
     * Wraps a handler so an unexpected failure becomes a generic 500 JSON error. The cause is
     * logged server-side only; without this the JDK server just drops the connection.
     */
    private static com.sun.net.httpserver.HttpHandler guarded(com.sun.net.httpserver.HttpHandler delegate) {
        return exchange -> {
            try {
                delegate.handle(exchange);
            } catch (Exception e) {
                System.err.println("[HTTP] " + exchange.getRequestMethod() + " "
                        + exchange.getRequestURI().getPath() + " failed: " + e);
                try {
                    sendJsonError(exchange, 500, "internal error");
                } catch (Exception alreadyResponding) {
                    exchange.close();
                }
            }
        };
    }

    private boolean authorizeApi(HttpExchange exchange) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        boolean browserRequest = origin != null || BrowserSessionManager.getSessionToken(cookie) != null;
        boolean authorized = browserRequest
                ? (origin == null || sessions.isAllowedOrigin(origin)) && sessions.isValidSessionCookie(cookie)
                : HmacAuth.verify(apiSecret, exchange.getRequestHeaders().getFirst("X-Signature"),
                        exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestURI().getQuery(), exchange.getRequestHeaders().getFirst("X-Timestamp"),
                        exchange.getRequestHeaders().getFirst("X-Nonce"));
        if (!authorized) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return false;
        }
        if (origin != null) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().set("Vary", "Origin");
        }
        return true;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    public static class StaticFileHandler implements com.sun.net.httpserver.HttpHandler {
        private final Path rootPath;

        private final java.util.function.Predicate<HttpExchange> secure;

        public StaticFileHandler(String rootDir) {
            this(rootDir, exchange -> false);
        }

        public StaticFileHandler(String rootDir, java.util.function.Predicate<HttpExchange> secure) {
            this.rootPath = Path.of(rootDir).toAbsolutePath().normalize();
            this.secure = secure;
        }

        public boolean isPathSafe(String requestPath) {
            if (requestPath == null || requestPath.indexOf('\0') >= 0) return false;
            try {
                String decoded = java.net.URLDecoder.decode(requestPath, StandardCharsets.UTF_8).replace('\\', '/');
                String clean = decoded.startsWith("/") ? decoded.substring(1) : decoded;
                Path candidate = rootPath.resolve(clean).normalize();
                return candidate.startsWith(rootPath);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            applySecurityHeaders(exchange, false, secure.test(exchange));
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.equals("/")) path = "/index.html";
            if (!isPathSafe(path)) {
                send(exchange, 403, "403 Forbidden");
                return;
            }
            String decoded = java.net.URLDecoder.decode(path, StandardCharsets.UTF_8).replace('\\', '/');
            String clean = decoded.startsWith("/") ? decoded.substring(1) : decoded;
            Path file = rootPath.resolve(clean).normalize();
            if (!file.startsWith(rootPath) || !Files.exists(file) || Files.isDirectory(file)) {
                send(exchange, 404, "404 Not Found");
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            String fileName = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (fileName.endsWith(".html")) exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            else if (fileName.endsWith(".css")) exchange.getResponseHeaders().set("Content-Type", "text/css; charset=utf-8");
            else if (fileName.endsWith(".js")) exchange.getResponseHeaders().set("Content-Type", "application/javascript; charset=utf-8");
            else if (fileName.endsWith(".svg")) exchange.getResponseHeaders().set("Content-Type", "image/svg+xml");
            // Build output under /assets/ is content-hashed, so it can be cached forever; the shell must be revalidated.
            exchange.getResponseHeaders().set("Cache-Control",
                    path.startsWith("/assets/") ? "public, max-age=31536000, immutable" : "no-cache");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }
}