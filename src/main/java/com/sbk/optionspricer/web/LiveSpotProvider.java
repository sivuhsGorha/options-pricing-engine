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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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

    private static final long CACHE_TTL_MS = 20_000L;
    private static final int HTTP_TIMEOUT_MS = 2500;
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

    /** Immutable, so a reader can never see a quote paired with another quote's fetch time. */
    private record CachedQuote(Quote quote, long fetchedAtMs) {}

    private final String finnhubKey;
    private final String polygonKey;
    private final String alphaVantageKey;
    private final String marketstackKey;
    private final HttpGetter http;

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

    public Quote getQuote(String symbol) {
        String sym = sanitizeSymbol(symbol);
        long now = System.currentTimeMillis();
        CachedQuote cached = cache.get(sym);
        if (cached != null && now - cached.fetchedAtMs() < CACHE_TTL_MS
                && (MarketDataStatus.LIVE == cached.quote().status() || MarketDataStatus.DELAYED == cached.quote().status())) {
            return cached.quote();
        }

        Quote fresh = fetchFromApis(sym);
        if (fresh != null) {
            cache.put(sym, new CachedQuote(fresh, now));
            return fresh;
        }
        if (cached != null) {
            Quote q = cached.quote();
            return new Quote(q.symbol(), q.bid(), q.ask(), q.last(), q.volume(), q.source(), q.timestamp(), MarketDataStatus.STALE);
        }
        return new Quote(sym, Double.NaN, Double.NaN, Double.NaN, 0L, "none", 0L, MarketDataStatus.UNAVAILABLE);
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
        try {
            // Finnhub accepts the key in a header, which keeps it out of URLs (and therefore logs and proxies).
            String url = "https://finnhub.io/api/v1/quote?symbol=" + symbol;
            return parseFinnhub(symbol, http.get(url, Map.of("X-Finnhub-Token", finnhubKey)));
        } catch (Exception ignored) {
            return null;
        }
    }

    Quote fetchAlphaVantage(String symbol) {
        if (alphaVantageKey == null) {
            return null;
        }
        try {
            String url = "https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=" + symbol
                    + "&apikey=" + alphaVantageKey;
            return parseAlphaVantage(symbol, http.get(url, Map.of()));
        } catch (Exception ignored) {
            return null;
        }
    }

    Quote fetchPolygonSnapshot(String symbol) {
        if (polygonKey == null) {
            return null;
        }
        try {
            String url = "https://api.polygon.io/v2/snapshot/locale/us/markets/stocks/tickers/" + symbol;
            return parsePolygonSnapshot(symbol, http.get(url, Map.of("Authorization", "Bearer " + polygonKey)));
        } catch (Exception ignored) {
            return null;
        }
    }

    Quote fetchMarketstack(String symbol) {
        if (marketstackKey == null) {
            return null;
        }
        try {
            String url = "https://api.marketstack.com/v1/eod/latest?access_key=" + marketstackKey
                    + "&symbols=" + symbol;
            return parseMarketstack(symbol, http.get(url, Map.of()));
        } catch (Exception ignored) {
            return null;
        }
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
            throw new IOException("HTTP " + code);
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
