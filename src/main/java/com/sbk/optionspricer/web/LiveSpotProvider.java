package com.sbk.optionspricer.web;

import com.sbk.optionspricer.config.EnvironmentConfigLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls SPY (or another equity) spot from configured market-data APIs.
 * HMAC {@code API_SECRET} only authenticates the dashboard; it is not a market feed.
 */
import com.sbk.optionspricer.market.MarketDataStatus;

public class LiveSpotProvider {

    private static final long CACHE_TTL_MS = 20_000L;
    private static final int HTTP_TIMEOUT_MS = 2500;

    public record Quote(String symbol, double bid, double ask, double last, long volume, String source, long timestamp, MarketDataStatus status) {}

    @FunctionalInterface
    public interface HttpGetter {
        String get(String url, Map<String, String> headers) throws IOException;
    }

    private final String finnhubKey;
    private final String polygonKey;
    private final String alphaVantageKey;
    private final String marketstackKey;
    private final HttpGetter http;

    private volatile Quote cache;
    private volatile long cacheAtMs;

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
        Quote cached = cache;
        if (cached != null && now - cacheAtMs < CACHE_TTL_MS
                && (MarketDataStatus.LIVE == cached.status() || MarketDataStatus.DELAYED == cached.status())) {
            return cached;
        }

        Quote fresh = fetchFromApis(sym);
        if (fresh != null) {
            cache = fresh;
            cacheAtMs = now;
            return fresh;
        }
        if (cached != null) {
            return new Quote(cached.symbol(), cached.bid(), cached.ask(), cached.last(), cached.volume(), cached.source(), cached.timestamp(), MarketDataStatus.STALE);
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
            String url = "https://finnhub.io/api/v1/quote?symbol=" + symbol + "&token=" + finnhubKey;
            String body = http.get(url, Map.of());
            Double px = parseFinnhubCurrent(body);
            if (px == null) {
                return null;
            }
            return new Quote(symbol, px - 0.01, px + 0.01, px, 2000L, "FINNHUB", System.currentTimeMillis(), MarketDataStatus.DELAYED);
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
            String body = http.get(url, Map.of());
            Double px = parseAlphaVantagePrice(body);
            if (px == null) {
                return null;
            }
            return new Quote(symbol, px - 0.01, px + 0.01, px, 2000L, "ALPHA_VANTAGE", System.currentTimeMillis(), MarketDataStatus.DELAYED);
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
            String body = http.get(url, Map.of("Authorization", "Bearer " + polygonKey));
            return parsePolygonSnapshot(symbol, body);
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
            String body = http.get(url, Map.of());
            Double px = parseMarketstackClose(body);
            if (px == null) {
                return null;
            }
            return new Quote(symbol, px - 0.01, px + 0.01, px, 2000L, "MARKETSTACK", System.currentTimeMillis(), MarketDataStatus.DELAYED);
        } catch (Exception ignored) {
            return null;
        }
    }

    static Double parseFinnhubCurrent(String json) {
        Double c = extractNumber(json, "c");
        if (c == null || c <= 0.0 || !Double.isFinite(c)) {
            return null;
        }
        return c;
    }

    static Double parseAlphaVantagePrice(String json) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"05\\. price\"\\s*:\\s*\"([0-9]+(?:\\.[0-9]+)?)\"").matcher(json);
        if (!m.find()) {
            return null;
        }
        double px = Double.parseDouble(m.group(1));
        return px > 0.0 ? px : null;
    }

    static Quote parsePolygonSnapshot(String symbol, String json) {
        if (json == null) return null;
        // Parse lastQuote object
        Matcher lastQuoteMatcher = Pattern.compile("\"lastQuote\"\\s*:\\s*\\{([^}]+)\\}").matcher(json);
        if (!lastQuoteMatcher.find()) return null;
        String lqJson = lastQuoteMatcher.group(1);

        Double bid = extractNumber("{" + lqJson + "}", "p");
        Double ask = extractNumber("{" + lqJson + "}", "P");
        if (bid == null || ask == null || bid <= 0.0 || ask <= 0.0 || bid >= ask) {
            return null;
        }

        Matcher lastTradeMatcher = Pattern.compile("\"lastTrade\"\\s*:\\s*\\{([^}]+)\\}").matcher(json);
        Double last = null;
        if (lastTradeMatcher.find()) {
            last = extractNumber("{" + lastTradeMatcher.group(1) + "}", "p");
        }
        if (last == null) last = bid; // fallback

        Matcher minMatcher = Pattern.compile("\"min\"\\s*:\\s*\\{([^}]+)\\}").matcher(json);
        Long volume = 2000L;
        if (minMatcher.find()) {
            Double v = extractNumber("{" + minMatcher.group(1) + "}", "v");
            if (v != null) volume = v.longValue();
        }

        Long timestamp = null;
        Matcher tMatcher = Pattern.compile("\"t\"\\s*:\\s*([0-9]+)").matcher(lqJson);
        if (tMatcher.find()) {
            timestamp = Long.parseLong(tMatcher.group(1));
            // Ensure nanoseconds convert to millis
            if (timestamp > 1_000_000_000_000_000L) {
                timestamp = timestamp / 1_000_000L;
            }
        }
        if (timestamp == null) timestamp = System.currentTimeMillis();

        return new Quote(symbol, bid, ask, last, volume, "POLYGON", timestamp, MarketDataStatus.LIVE);
    }

    static Double parseMarketstackClose(String json) {
        Double c = extractNumber(json, "close");
        if (c == null || c <= 0.0) {
            return null;
        }
        return c;
    }

    static Double extractNumber(String json, String key) {
        if (json == null) {
            return null;
        }
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
        Matcher m = p.matcher(json);
        if (!m.find()) {
            return null;
        }
        return Double.parseDouble(m.group(1));
    }

    public static String toJson(Quote q) {
        String price = Double.isNaN(q.last())
                ? "null"
                : String.format(Locale.US, "%.2f", q.last());
        return String.format(Locale.US,
                "{\n  \"symbol\": \"%s\",\n  \"spotPrice\": %s,\n  \"source\": \"%s\",\n  \"timestamp\": %d,\n  \"status\": \"%s\"\n}",
                q.symbol(), price, q.source(), q.timestamp(), q.status());
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
