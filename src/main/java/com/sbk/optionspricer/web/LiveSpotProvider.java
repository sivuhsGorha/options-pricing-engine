package com.sbk.optionspricer.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbk.optionspricer.config.EnvironmentConfigLoader;
import com.sbk.optionspricer.market.MarketDataStatus;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Pulls SPY (or another equity) spot from configured market-data APIs.
 * HMAC {@code API_SECRET} only authenticates the dashboard; it is not a market feed.
 *
 * <p>Every quote carries the time its price was actually set, taken from the provider's response
 * (Finnhub {@code t}, Polygon quote time, or the close of the trading day for end-of-day sources).
 * A quote whose time is unknown has timestamp 0, so consumers see it as very old rather than current.
 */
public class LiveSpotProvider {

    private static final int HTTP_TIMEOUT_MS = 2500;
    /** How long a first-time caller (nothing cached) waits for the network before reporting UNAVAILABLE. */
    private static final long FIRST_FETCH_WAIT_MS = 3_000L;
    /** A served quote older than its lifetime by this much is labelled STALE: its refresh is evidently failing. */
    private static final long STALE_AFTER_EXPIRY_MS = 60_000L;
    private static final long FAILURE_LOG_INTERVAL_MS = 60_000L;
    private static final int MAX_CACHED_SYMBOLS = 64;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);

    /**
     * Bid/ask are real only for sources that publish a book (Polygon); other sources give a last
     * price and the spread is a nominal +/- 1c around it. Timestamp is epoch millis of the price, or 0 if unknown.
     */
    public record Quote(String symbol, double bid, double ask, double last, long volume, String source, long timestamp, MarketDataStatus status) {}

    @FunctionalInterface
    public interface HttpGetter {
        String get(String url, Map<String, String> headers) throws IOException;
    }

    /** Thrown for HTTP error statuses so callers can tell throttling (429) and rejected credentials apart from outages. */
    public static final class HttpStatusException extends IOException {
        private final int status;
        private final long retryAfterSeconds;

        public HttpStatusException(int status, long retryAfterSeconds) {
            super("HTTP " + status);
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int status() {
            return status;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private enum Source {
        POLYGON(20_000L), FINNHUB(20_000L), ALPHA_VANTAGE(900_000L), MARKETSTACK(21_600_000L);

        /** How long a quote from this source is considered current: end-of-day feeds change at most daily. */
        final long ttlMs;

        Source(long ttlMs) {
            this.ttlMs = ttlMs;
        }

        static Source of(String name) {
            for (Source s : values()) {
                if (s.name().equals(name)) return s;
            }
            return FINNHUB;
        }
    }

    /** At most {@code maxCalls} calls in any {@code windowMs}; the free-plan limits of each provider. */
    private record Budget(int maxCalls, long windowMs) {}

    @FunctionalInterface
    private interface Fetch {
        Quote run() throws IOException;
    }

    /**
     * Per-source admission control: exponential backoff after failures, longer cool-downs after
     * throttling or rejected credentials, and call budgets that stay inside the provider's plan.
     */
    private static final class SourceGate {
        private final Source source;
        private final java.util.List<Budget> budgets;
        private final java.util.List<Deque<Long>> calls = new java.util.ArrayList<>();
        private int failures;
        private long blockedUntilMs;
        private long lastLoggedMs;

        SourceGate(Source source, Budget... budgets) {
            this.source = source;
            this.budgets = java.util.List.of(budgets);
            for (int i = 0; i < budgets.length; i++) calls.add(new ArrayDeque<>());
        }

        synchronized boolean admit(long now) {
            if (now < blockedUntilMs) return false;
            for (int i = 0; i < budgets.size(); i++) {
                Deque<Long> window = calls.get(i);
                while (!window.isEmpty() && now - window.peekFirst() >= budgets.get(i).windowMs()) window.pollFirst();
                if (window.size() >= budgets.get(i).maxCalls()) return false;
            }
            for (Deque<Long> window : calls) window.addLast(now);
            return true;
        }

        synchronized void succeeded() {
            failures = 0;
            blockedUntilMs = 0L;
        }

        /** Exponential backoff: 5s, 10s, 20s ... capped at 5 minutes. */
        synchronized long failed(long now) {
            failures++;
            long delay = Math.min(300_000L, 5_000L << Math.min(failures - 1, 6));
            blockedUntilMs = now + delay;
            return delay;
        }

        synchronized long coolDown(long now, long ms) {
            failures = Math.max(failures, 1);
            blockedUntilMs = now + ms;
            return ms;
        }

        synchronized boolean shouldLog(long now) {
            if (lastLoggedMs == 0L || now - lastLoggedMs >= FAILURE_LOG_INTERVAL_MS) {
                lastLoggedMs = now;
                return true;
            }
            return false;
        }
    }

    /** Immutable, so a reader can never see a quote paired with another quote's fetch time. */
    private record CachedQuote(Quote quote, long fetchedAtMs) {}

    private final String finnhubKey;
    private final String polygonKey;
    private final String alphaVantageKey;
    private final String marketstackKey;
    private final HttpGetter http;
    private final LongSupplier clock;
    private final Consumer<String> log;
    private final Map<Source, SourceGate> gates = new EnumMap<>(Source.class);
    private final Map<String, CompletableFuture<Quote>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService refreshExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "quote-refresh");
        thread.setDaemon(true);
        return thread;
    });

    /** Per-symbol cache, bounded so arbitrary symbol requests cannot grow it without limit. */
    private final Map<String, CachedQuote> cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedQuote> eldest) {
            return size() > MAX_CACHED_SYMBOLS;
        }
    });

    public LiveSpotProvider() {
        this(EnvironmentConfigLoader.get("FINNHUB_KEY"),
                EnvironmentConfigLoader.get("POLYGON_API_KEY"),
                EnvironmentConfigLoader.get("ALPHA_VANTAGE_KEY"),
                EnvironmentConfigLoader.get("MARKETSTACK_KEY"),
                LiveSpotProvider::httpGet);
    }

    public LiveSpotProvider(String finnhubKey, String polygonKey, String alphaVantageKey,
                            String marketstackKey, HttpGetter http) {
        this(finnhubKey, polygonKey, alphaVantageKey, marketstackKey, http, System::currentTimeMillis, System.err::println);
    }

    /** Package-private: lets tests control the clock and capture log output. */
    LiveSpotProvider(String finnhubKey, String polygonKey, String alphaVantageKey,
                     String marketstackKey, HttpGetter http, LongSupplier clock, Consumer<String> log) {
        this.clock = clock;
        this.log = log;
        // Free-plan limits: Polygon 5/min; Finnhub 60/min; Alpha Vantage 5/min and 25/day; Marketstack 100/month.
        gates.put(Source.POLYGON, new SourceGate(Source.POLYGON, new Budget(5, 60_000L)));
        gates.put(Source.FINNHUB, new SourceGate(Source.FINNHUB, new Budget(50, 60_000L)));
        gates.put(Source.ALPHA_VANTAGE, new SourceGate(Source.ALPHA_VANTAGE, new Budget(5, 60_000L), new Budget(25, 86_400_000L)));
        gates.put(Source.MARKETSTACK, new SourceGate(Source.MARKETSTACK, new Budget(100, 30L * 86_400_000L)));
        this.finnhubKey = blankToNull(finnhubKey);
        this.polygonKey = blankToNull(polygonKey);
        this.alphaVantageKey = blankToNull(alphaVantageKey);
        this.marketstackKey = blankToNull(marketstackKey);
        this.http = http;
    }

    public boolean hasMarketDataKeys() {
        return finnhubKey != null || polygonKey != null || alphaVantageKey != null || marketstackKey != null;
    }

    private static final Pattern VALID_SYMBOL_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,16}$");

    public static String sanitizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "SPY";
        }
        String trimmed = symbol.trim().toUpperCase(Locale.ROOT);
        if (!VALID_SYMBOL_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid ticker symbol format: " + symbol);
        }
        return trimmed;
    }

    public Map<String, String> getFeedStatus(String symbol) {
        String sym = sanitizeSymbol(symbol);
        Quote fh = finnhubKey != null ? fetchFinnhub(sym) : null;
        Quote av = alphaVantageKey != null ? fetchAlphaVantage(sym) : null;
        Quote pl = polygonKey != null ? fetchPolygonSnapshot(sym) : null;
        Quote ms = marketstackKey != null ? fetchMarketstack(sym) : null;

        Map<String, String> status = new java.util.LinkedHashMap<>();

        status.put("FINNHUB", fh != null ? fh.status().name() : "UNAVAILABLE");
        status.put("ALPHA_VANTAGE", av != null ? av.status().name() : "UNAVAILABLE");
        status.put("POLYGON", pl != null ? pl.status().name() : "UNAVAILABLE");
        status.put("MARKETSTACK", ms != null ? ms.status().name() : "UNAVAILABLE");
        return status;
    }

    /**
     * Returns the best known quote without making the caller wait on the network: a current cached
     * quote is returned as is; an expired one is returned immediately while a single background
     * refresh runs. Only a caller with nothing cached waits (briefly) for the first fetch.
     */
    public Quote getQuote(String symbol) {
        String sym = sanitizeSymbol(symbol);
        long now = clock.getAsLong();
        CachedQuote cached = cache.get(sym);
        long ttl = cached == null ? 0L : Source.of(cached.quote().source()).ttlMs;
        if (cached != null && now - cached.fetchedAtMs() < ttl) {
            return cached.quote();
        }

        CompletableFuture<Quote> refresh = refresh(sym);
        if (cached != null) {
            Quote q = cached.quote();
            MarketDataStatus status = now - cached.fetchedAtMs() >= ttl + STALE_AFTER_EXPIRY_MS ? MarketDataStatus.STALE : q.status();
            return new Quote(q.symbol(), q.bid(), q.ask(), q.last(), q.volume(), q.source(), q.timestamp(), status);
        }
        try {
            Quote fresh = refresh.get(FIRST_FETCH_WAIT_MS, TimeUnit.MILLISECONDS);
            if (fresh != null) {
                return fresh;
            }
        } catch (Exception ignored) {
            // timed out, interrupted or failed: report unavailable below; the refresh may still land later
        }
        return new Quote(sym, Double.NaN, Double.NaN, Double.NaN, 0L, "none", 0L, MarketDataStatus.UNAVAILABLE);
    }

    /** One refresh per symbol at a time; concurrent callers share it. */
    private CompletableFuture<Quote> refresh(String symbol) {
        return inFlight.computeIfAbsent(symbol, sym -> CompletableFuture.supplyAsync(() -> {
            try {
                Quote fresh = fetchFromApis(sym);
                if (fresh != null) {
                    cache.put(sym, new CachedQuote(fresh, clock.getAsLong()));
                }
                return fresh;
            } finally {
                inFlight.remove(sym);
            }
        }, refreshExecutor));
    }

    private Quote fetchFromApis(String symbol) {
        Quote q = fetchPolygonSnapshot(symbol);
        if (q != null) {
            return q;
        }
        q = fetchFinnhub(symbol);
        if (q != null) {
            return q;
        }
        q = fetchAlphaVantage(symbol);
        if (q != null) {
            return q;
        }
        return fetchMarketstack(symbol);
    }

    Quote fetchFinnhub(String symbol) {
        if (finnhubKey == null) {
            return null;
        }
        return gated(Source.FINNHUB, () -> {
            // Finnhub accepts the key in a header, which keeps it out of URLs (and therefore logs and proxies).
            String url = "https://finnhub.io/api/v1/quote?symbol=" + symbol;
            return parseFinnhub(symbol, http.get(url, Map.of("X-Finnhub-Token", finnhubKey)));
        });
    }

    Quote fetchAlphaVantage(String symbol) {
        if (alphaVantageKey == null) {
            return null;
        }
        return gated(Source.ALPHA_VANTAGE, () -> {
            String url = "https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=" + symbol
                    + "&apikey=" + alphaVantageKey;
            String body = http.get(url, Map.of());
            if (isAlphaVantageNotice(body)) {
                // A 200 response carrying a rate-limit message: treat as throttling and leave it alone for 10 minutes.
                throw new HttpStatusException(429, 600);
            }
            return parseAlphaVantage(symbol, body);
        });
    }

    Quote fetchPolygonSnapshot(String symbol) {
        if (polygonKey == null) {
            return null;
        }
        return gated(Source.POLYGON, () -> {
            String url = "https://api.polygon.io/v2/snapshot/locale/us/markets/stocks/tickers/" + symbol;
            return parsePolygonSnapshot(symbol, http.get(url, Map.of("Authorization", "Bearer " + polygonKey)));
        });
    }

    Quote fetchMarketstack(String symbol) {
        if (marketstackKey == null) {
            return null;
        }
        return gated(Source.MARKETSTACK, () -> {
            String url = "https://api.marketstack.com/v1/eod/latest?access_key=" + marketstackKey
                    + "&symbols=" + symbol;
            return parseMarketstack(symbol, http.get(url, Map.of()));
        });
    }

    /** Runs a provider call if its gate admits it; records the outcome and reports failures (credentials redacted). */
    private Quote gated(Source source, Fetch fetch) {
        SourceGate gate = gates.get(source);
        long now = clock.getAsLong();
        if (!gate.admit(now)) {
            return null;
        }
        try {
            Quote quote = fetch.run();
            gate.succeeded();
            return quote;
        } catch (HttpStatusException e) {
            long wait;
            String reason;
            if (e.status() == 429) {
                wait = gate.coolDown(now, e.retryAfterSeconds() > 0 ? Math.min(e.retryAfterSeconds(), 3_600L) * 1_000L : 60_000L);
                reason = "rate limited (HTTP 429)";
            } else if (e.status() == 401 || e.status() == 403) {
                wait = gate.coolDown(now, 900_000L);
                reason = "credentials rejected (HTTP " + e.status() + ")";
            } else {
                wait = gate.failed(now);
                reason = "HTTP " + e.status();
            }
            logFailure(gate, source, reason, wait, now);
            return null;
        } catch (Exception e) {
            long wait = gate.failed(now);
            logFailure(gate, source, redact(e.toString()), wait, now);
            return null;
        }
    }

    private void logFailure(SourceGate gate, Source source, String reason, long waitMs, long now) {
        if (gate.shouldLog(now)) {
            log.accept("[MARKET DATA] " + source + " unavailable: " + reason + "; not retrying for " + waitMs / 1000 + "s");
        }
    }

    private static boolean isAlphaVantageNotice(String body) {
        JsonNode root = parseJson(body);
        return root != null && (root.has("Note") || root.has("Information"));
    }

    private static final Pattern CREDENTIAL_PARAM =
            Pattern.compile("(?i)\\b(api_?key|access_key|token|key)=([^&\\s\"']+)");

    /**
     * Masks credential query parameters. Alpha Vantage and Marketstack only accept their key as a
     * query parameter, so any URL or exception message must pass through this before it is logged.
     */
    static String redact(String text) {
        return text == null ? null : CREDENTIAL_PARAM.matcher(text).replaceAll("$1=***");
    }

    // ---------------------------------------------------------------- parsing

    /** Finnhub /quote: {@code c} = current price, {@code t} = time of that price in epoch seconds. */
    static Quote parseFinnhub(String symbol, String body) {
        JsonNode root = parseJson(body);
        Double price = positive(root == null ? null : root.get("c"));
        if (price == null) {
            return null;
        }
        long timestamp = normalizeEpochMillis(longValue(root.get("t")));
        return new Quote(symbol, price - 0.01, price + 0.01, price, 2000L, "FINNHUB", timestamp, MarketDataStatus.DELAYED);
    }

    /** Alpha Vantage GLOBAL_QUOTE. Rate-limit and error notices arrive as 200 responses without a quote. */
    static Quote parseAlphaVantage(String symbol, String body) {
        JsonNode root = parseJson(body);
        if (root == null || root.has("Note") || root.has("Information") || root.has("Error Message")) {
            return null;
        }
        JsonNode quote = root.get("Global Quote");
        Double price = positive(quote == null ? null : quote.get("05. price"));
        if (price == null) {
            return null;
        }
        long timestamp = closeOfTradingDay(quote.get("07. latest trading day"));
        return new Quote(symbol, price - 0.01, price + 0.01, price, 2000L, "ALPHA_VANTAGE", timestamp, MarketDataStatus.DELAYED);
    }

    /** Polygon single-ticker snapshot: real bid/ask from lastQuote, last trade if present, else the midpoint. */
    static Quote parsePolygonSnapshot(String symbol, String body) {
        JsonNode root = parseJson(body);
        if (root == null) {
            return null;
        }
        JsonNode ticker = root.get("ticker") != null && root.get("ticker").isObject() ? root.get("ticker") : root;
        JsonNode lastQuote = ticker.get("lastQuote");
        if (lastQuote == null || !lastQuote.isObject()) {
            return null;
        }
        Double bid = positive(lastQuote.get("p"));
        Double ask = positive(lastQuote.get("P"));
        if (bid == null || ask == null || bid >= ask) {
            return null;
        }
        JsonNode lastTrade = ticker.get("lastTrade");
        Double last = positive(lastTrade == null ? null : lastTrade.get("p"));
        if (last == null) {
            last = (bid + ask) / 2.0; // no trade in the snapshot: the midpoint, not a price that never traded
        }
        JsonNode minute = ticker.get("min");
        Double minuteVolume = positive(minute == null ? null : minute.get("v"));
        long volume = minuteVolume != null ? minuteVolume.longValue() : 2000L;

        long timestamp = normalizeEpochMillis(longValue(lastQuote.get("t")));
        if (timestamp == 0L && lastTrade != null) {
            timestamp = normalizeEpochMillis(longValue(lastTrade.get("t")));
        }
        return new Quote(symbol, bid, ask, last, volume, "POLYGON", timestamp, MarketDataStatus.LIVE);
    }

    /** Marketstack end-of-day: the quote is the close of the bar's trading date. */
    static Quote parseMarketstack(String symbol, String body) {
        JsonNode root = parseJson(body);
        JsonNode data = root == null ? null : root.get("data");
        JsonNode bar = data != null && data.isArray() && !data.isEmpty() ? data.get(0) : null;
        Double price = positive(bar == null ? null : bar.get("close"));
        if (price == null) {
            return null;
        }
        long timestamp = closeOfTradingDay(bar.get("date"));
        return new Quote(symbol, price - 0.01, price + 0.01, price, 2000L, "MARKETSTACK", timestamp, MarketDataStatus.DELAYED);
    }

    private static JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** A finite, strictly positive number from a JSON number or numeric string; otherwise null. */
    private static Double positive(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        double value;
        if (node.isNumber()) {
            value = node.asDouble();
        } else if (node.isTextual()) {
            try {
                value = Double.parseDouble(node.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        return Double.isFinite(value) && value > 0.0 ? value : null;
    }

    private static long longValue(JsonNode node) {
        return node != null && node.isIntegralNumber() ? node.asLong() : 0L;
    }

    /**
     * Epoch value in seconds, milliseconds, microseconds or nanoseconds (providers differ) to epoch
     * millis. 0 means unknown.
     */
    static long normalizeEpochMillis(long value) {
        if (value <= 0L) return 0L;
        if (value >= 100_000_000_000_000_000L) return value / 1_000_000L; // nanoseconds
        if (value >= 100_000_000_000_000L) return value / 1_000L;         // microseconds
        if (value >= 100_000_000_000L) return value;                      // milliseconds
        return value * 1_000L;                                            // seconds
    }

    /** Close (16:00 New York) of the trading day named by the first 10 characters of the value; 0 if unusable. */
    private static long closeOfTradingDay(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().length() < 10) {
            return 0L;
        }
        try {
            LocalDate day = LocalDate.parse(node.asText().substring(0, 10));
            return day.atTime(MARKET_CLOSE).atZone(MARKET_ZONE).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    public static String toJson(Quote q) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", q.symbol());
        body.put("spotPrice", Double.isFinite(q.last()) ? Json.round(q.last(), 2) : null);
        body.put("source", q.source());
        body.put("timestamp", q.timestamp());
        body.put("status", q.status().name());
        return Json.write(body);
    }

    static String httpGet(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(HTTP_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "AURA-OPT/1.0");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = "";
        if (stream != null) {
            try (InputStream in = stream) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (code >= 400) {
            long retryAfter = 0L;
            String header = conn.getHeaderField("Retry-After");
            if (header != null) {
                try {
                    retryAfter = Long.parseLong(header.trim());
                } catch (NumberFormatException ignored) {
                    // an HTTP-date form: fall back to the default cool-down
                }
            }
            throw new HttpStatusException(code, retryAfter);
        }
        return body;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
