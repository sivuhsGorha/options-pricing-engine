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
    public static volatile int publicServerPort = 8080;

    private final String apiSecret;
    private final BrowserSessionManager sessions;
    private final MmapStateReader mmapReader;
    private final HttpServer server;
    private final ExecutorService httpExecutor;
    private final WebSocketDashboardServer wsServer;
    
    private final OrderManager orderManager;
    private final PositionTracker positionTracker;
    private final MarketSnapshotAdapter marketAdapter;

    public OptionsDashboardServer(String apiSecret, String operatorPassword, String allowedOrigins,
                                  String bindAddress, int port, int wsPort, MmapStateReader mmapReader,
                                  String webRoot, OrderManager orderManager, PositionTracker positionTracker,
                                  MarketSnapshotAdapter marketAdapter) throws IOException {
        try {
            validateApiSecret(apiSecret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        this.apiSecret = apiSecret;
        this.sessions = new BrowserSessionManager(operatorPassword, allowedOrigins);
        this.mmapReader = mmapReader;
        this.orderManager = orderManager;
        this.positionTracker = positionTracker;
        this.marketAdapter = marketAdapter;
        InetAddress address = InetAddress.getByName(bindAddress);
        this.server = HttpServer.create(new InetSocketAddress(address, port), 0);
        this.httpExecutor = Executors.newFixedThreadPool(10);
        this.server.setExecutor(httpExecutor);
        this.wsServer = new WebSocketDashboardServer(bindAddress, wsPort, mmapReader, apiSecret, sessions);
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

        return String.format(java.util.Locale.US,
                "{\"symbol\":\"%s\",\"sourceStatus\":\"%s\",\"lastUpdate\":%d,\"quoteAgeMs\":%d,\"tradable\":%b}",
                symbol, sourceStatus, lastUpdate, quoteAge, tradable);
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

    private void configureContexts(String webRoot) {
        server.createContext("/login", this::handleLogin);
        server.createContext("/logout", this::handleLogout);
        server.createContext("/api/spot", exchange -> {
            applySecurityHeaders(exchange, true);
            if (!authorizeApi(exchange)) return;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            MarketSnapshot snapshot = marketAdapter.getSnapshot("SPY");
            String snapshotJson = String.format(java.util.Locale.US,
                "{\"symbol\": \"%s\", \"spotPrice\": %.2f, \"source\": \"%s\", \"timestamp\": %d, \"status\": \"%s\"}",
                snapshot.symbol(), snapshot.last(), snapshot.source(), snapshot.timestamp().toEpochMilli(), snapshot.status().name());
            send(exchange, 200, snapshotJson);
        });

        server.createContext("/api/health", exchange -> {
            applySecurityHeaders(exchange, true);
            if (!authorizeApi(exchange)) return;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            send(exchange, 200, healthSnapshot(marketAdapter));
        });

        server.createContext("/api/execution", exchange -> {
            applySecurityHeaders(exchange, true);
            if (!authorizeApi(exchange)) return;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            StringBuilder json = new StringBuilder("[");
            if (orderManager != null) {
                var audits = orderManager.getAuditTrail();
                boolean first = true;
                for (var a : audits) {
                    if (!first) json.append(",");
                    first = false;
                    json.append(String.format(java.util.Locale.US,
                        "{\"orderId\":%d,\"accepted\":%b,\"sourceStatus\":\"%s\",\"rejectionReason\":\"%s\",\"fillPrice\":%.2f,\"quantity\":%d,\"timestamp\":%d}",
                        a.orderId(), a.accepted(), a.sourceStatus().name(), a.rejectionReason(), a.fillPrice(), a.quantity(), a.timestamp().toEpochMilli()));
                }
            }
            json.append("]");
            send(exchange, 200, json.toString());
        });

        server.createContext("/api/risk", exchange -> {
            applySecurityHeaders(exchange, true);
            if (!authorizeApi(exchange)) return;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try {
                MmapStateReader.RiskState state = mmapReader.readState();
                double netDelta = sanitizeRiskValue(state.netDelta, 0.0);
                double netGamma = sanitizeRiskValue(state.netGamma, 0.0);
                double netVega = sanitizeRiskValue(state.netVega, 0.0);
                double scenarioMargin = sanitizeRiskValue(state.scenarioMargin, 0.0);
                PositionTracker.PortfolioExposure trackedExposure = PositionTracker.snapshotPortfolioExposure();
                double trackedNetDelta = sanitizeRiskValue(trackedExposure.netDelta(), 0.0);
                double trackedNetGamma = sanitizeRiskValue(trackedExposure.netGamma(), 0.0);
                double trackedNetVega = sanitizeRiskValue(trackedExposure.netVega(), 0.0);
                double trackedNotional = sanitizeRiskValue(trackedExposure.netNotional(), 0.0);
                int hedgeQty = (int) Math.round(-netDelta);
                double optMargin = scenarioMargin > 0.0 ? Math.max(12500.0, scenarioMargin * 0.086) : 0.0;
                double reductionPct = scenarioMargin > 0.0 ? ((scenarioMargin - optMargin) / scenarioMargin) * 100.0 : 0.0;
                String json = String.format(java.util.Locale.US,
                        "{\"netDelta\":%.2f,\"netGamma\":%.2f,\"netVega\":%.2f," +
                                "\"scenarioMargin\":%.2f,\"recommendedHedge\":%d," +
                                "\"optimizedMargin\":%.2f,\"marginReductionPct\":%.1f," +
                                "\"trackedNetDelta\":%.2f,\"trackedNetGamma\":%.2f,\"trackedNetVega\":%.2f," +
                                "\"trackedNotional\":%.2f,\"l3FillProb\":75.0,\"sorAllocations\":\"EUREX: 50%% | OPTIQ: 30%% | SOLA: 20%%\"}",
                        netDelta, netGamma, netVega, scenarioMargin, hedgeQty, optMargin, reductionPct,
                        trackedNetDelta, trackedNetGamma, trackedNetVega, trackedNotional);
                System.out.println("Serving risk state: " + json);
                send(exchange, 200, json);
            } catch (IllegalStateException e) {
                System.out.println("Serving ZERO risk state due to IllegalStateException");
                send(exchange, 200, "{\"netDelta\":0.00,\"netGamma\":0.00,\"netVega\":0.00,\"scenarioMargin\":0.00,\"recommendedHedge\":0,\"optimizedMargin\":0.00,\"marginReductionPct\":0.0,\"l3FillProb\":75.0,\"sorAllocations\":\"EUREX: 50% | OPTIQ: 30% | SOLA: 20%\"}");
            }
        });

        server.createContext("/api/surface3d", exchange -> {
            applySecurityHeaders(exchange, true);
            if (!authorizeApi(exchange)) return;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            String query = exchange.getRequestURI().getQuery();
            String model = query != null && query.contains("model=SSVI") ? "SSVI" :
                    query != null && query.contains("model=FREE_SABR") ? "FREE_SABR" : "SABR";
            int[] strikes = {50, 65, 80, 90, 100, 110, 120, 135, 150};
            double[] expiries = {0.1, 0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0};
            StringBuilder json = new StringBuilder("{\"model\":\"").append(model).append("\",\"x\":[");
            for (int i = 0; i < strikes.length; i++) {
                if (i > 0) json.append(',');
                json.append(strikes[i]);
            }
            json.append("],\"y\":[");
            for (int i = 0; i < expiries.length; i++) {
                if (i > 0) json.append(',');
                json.append(String.format(java.util.Locale.US, "%.2f", expiries[i]));
            }
            json.append("],\"z\":[");
            com.sbk.optionspricer.volatility.SsviApproximation.SsviParams ssviParams =
                    new com.sbk.optionspricer.volatility.SsviApproximation.SsviParams(0.55, 0.25, -0.50);
            for (int i = 0; i < expiries.length; i++) {
                if (i > 0) json.append(',');
                json.append('[');
                for (int j = 0; j < strikes.length; j++) {
                    if (j > 0) json.append(',');
                    double vol = "SSVI".equals(model)
                            ? com.sbk.optionspricer.volatility.SsviApproximation.impliedVol(100.0, strikes[j], expiries[i], 0.22, ssviParams)
                            : "FREE_SABR".equals(model)
                            ? com.sbk.optionspricer.volatility.SabrFreeBoundaryModel.impliedVolatility(100.0, strikes[j], expiries[i], 0.30, 0.6, -0.65, 0.60)
                            : SabrModel.impliedVolatility(100.0, strikes[j], expiries[i], 0.35, 0.5, -0.75, 0.85);
                    json.append(String.format(java.util.Locale.US, "%.4f", vol));
                }
                json.append(']');
            }
            json.append("]}");
            send(exchange, 200, json.toString());
        });
        server.createContext("/", new StaticFileHandler(webRoot));
    }

    public static void applySecurityHeaders(HttpExchange exchange, boolean isApi) {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Security-Policy",
                "default-src 'self'; " +
                "script-src 'self' 'unsafe-inline' https://cdn.plot.ly; " +
                "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
                "font-src 'self' https://fonts.gstatic.com; " +
                "connect-src 'self' ws: wss:; " +
                "img-src 'self' data:; " +
                "object-src 'none'; " +
                "frame-ancestors 'none'; " +
                "base-uri 'self'; " +
                "form-action 'self'");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
        headers.set("Permissions-Policy", "geolocation=(), camera=(), microphone=(), payment=()");
        if (isSecureRequest(exchange)) {
            headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        if (isApi) {
            headers.set("Cache-Control", "no-store, no-cache, must-revalidate");
            headers.set("Pragma", "no-cache");
        }
    }

    private static boolean isSecureRequest(HttpExchange exchange) {
        String proto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
        if (proto != null && "https".equalsIgnoreCase(proto.trim())) {
            return true;
        }
        return "https".equalsIgnoreCase(exchange.getRequestURI().getScheme());
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        applySecurityHeaders(exchange, true);
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
        BrowserSessionManager.LoginResult result = sessions.login(
                new String(body, StandardCharsets.UTF_8), exchange.getRemoteAddress());
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
        applySecurityHeaders(exchange, true);
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

        public StaticFileHandler(String rootDir) {
            this.rootPath = Path.of(rootDir).toAbsolutePath().normalize();
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
            applySecurityHeaders(exchange, false);
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
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }
}