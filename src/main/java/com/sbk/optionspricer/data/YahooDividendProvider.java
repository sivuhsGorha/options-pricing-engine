package com.sbk.optionspricer.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbk.optionspricer.web.LiveSpotProvider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Fetches historical dividend payouts from Yahoo Finance.
 *
 * <p>Uses an unofficial, undocumented endpoint, so treat it as best effort: requests have timeouts,
 * the symbol is validated, history is cached for hours (it changes a few times a year), and a
 * failure reports the symbol only, never the request URL.
 */
public class YahooDividendProvider {

    private static final String YAHOO_DIVIDEND_URL = "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=1d&range=2y&events=div";
    private static final long CACHE_TTL_MS = 6 * 3_600_000L;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Returns the response body for a symbol, or throws. */
    @FunctionalInterface
    public interface Fetcher {
        String fetch(String symbol) throws IOException;
    }

    public record HistoricalDividend(LocalDate exDate, double amount) {}

    private record Cached(List<HistoricalDividend> dividends, long atMs) {}

    private final Fetcher fetcher;
    private final LongSupplier clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public YahooDividendProvider() {
        this(httpFetcher(), System::currentTimeMillis);
    }

    YahooDividendProvider(Fetcher fetcher, LongSupplier clock) {
        this.fetcher = fetcher;
        this.clock = clock;
    }

    public List<HistoricalDividend> getHistoricalDividends(String symbol) {
        String sym = LiveSpotProvider.sanitizeSymbol(symbol); // rejects anything that could alter the request path
        long now = clock.getAsLong();
        Cached cached = cache.get(sym);
        if (cached != null && now - cached.atMs() < CACHE_TTL_MS) {
            return cached.dividends();
        }
        List<HistoricalDividend> fetched;
        try {
            fetched = parseDividends(fetcher.fetch(sym));
        } catch (Exception e) {
            // Deliberately omits the cause message: it can embed the full request URL.
            throw new IllegalStateException("Dividend history unavailable for " + sym);
        }
        cache.put(sym, new Cached(fetched, now));
        return fetched;
    }

    /** Dividends are an object keyed by timestamp: {@code chart.result[0].events.dividends}. */
    static List<HistoricalDividend> parseDividends(String json) {
        List<HistoricalDividend> dividends = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return dividends;
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            return dividends;
        }
        JsonNode result = root == null ? null : root.path("chart").path("result");
        JsonNode events = result != null && result.isArray() && !result.isEmpty() ? result.get(0).path("events").path("dividends") : null;
        if (events == null || !events.isObject()) {
            return dividends;
        }
        Iterator<Map.Entry<String, JsonNode>> entries = events.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            JsonNode value = entry.getValue();
            JsonNode amountNode = value.get("amount");
            if (amountNode == null || !amountNode.isNumber() || !(amountNode.asDouble() > 0.0) || !Double.isFinite(amountNode.asDouble())) {
                continue;
            }
            long epochSeconds = value.has("date") && value.get("date").isIntegralNumber() ? value.get("date").asLong() : parseLongOrZero(entry.getKey());
            if (epochSeconds <= 0L) {
                continue;
            }
            // UTC, not the machine's zone: an ex-date must not change with where the process runs.
            dividends.add(new HistoricalDividend(Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate(), amountNode.asDouble()));
        }
        dividends.sort(Comparator.comparing(HistoricalDividend::exDate));
        return dividends;
    }

    private static long parseLongOrZero(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return symbol -> {
            URI uri = URI.create(String.format(YAHOO_DIVIDEND_URL, URLEncoder.encode(symbol, StandardCharsets.UTF_8)));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        };
    }
}
