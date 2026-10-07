package com.sbk.optionspricer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Option chains from Cboe's public delayed-quotes feed: every listed expiry of a symbol in one JSON document
 * with bid, ask, implied volatility, volume and open interest, 15 minutes delayed, no API key.
 *
 * <p>The document is fetched once per symbol and reused for {@link #CACHE_TTL}, so listing expiries and
 * loading several chains costs one request. Contracts are identified by their OCC symbol
 * ({@code SPY261120C00500000} = root, yyMMdd expiry, C/P, strike x 1000); anything that does not parse is
 * skipped. The quotes are real market data, delayed: the source name says so and the chain's note carries the
 * feed's own timestamp.
 */
public final class CboeOptionChain implements OptionChainProvider {

    public static final String SOURCE = "CBOE_DELAYED";
    static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String URL_TEMPLATE = "https://cdn.cboe.com/api/global/delayed_quotes/options/%s.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SYMBOL = Pattern.compile("^[A-Z0-9._-]{1,10}$");

    @FunctionalInterface
    public interface Fetcher {
        String get(String url) throws IOException;
    }

    /** One parsed feed document. */
    record Document(String symbol, double spot, String asOf, Map<LocalDate, List<OptionQuote>> byExpiry, Instant fetchedAt) {}

    private final Fetcher fetcher;
    private final Clock clock;
    private final Map<String, Document> cache = new ConcurrentHashMap<>();

    public CboeOptionChain() {
        this(CboeOptionChain::httpGet, Clock.systemUTC());
    }

    CboeOptionChain(Fetcher fetcher, Clock clock) {
        if (fetcher == null || clock == null) {
            throw new IllegalArgumentException("fetcher and clock must not be null");
        }
        this.fetcher = fetcher;
        this.clock = clock;
    }

    @Override
    public String sourceName() {
        return SOURCE;
    }

    @Override
    public List<LocalDate> listExpiries(String symbol, LocalDate asOf) {
        Document document = document(symbol);
        List<LocalDate> expiries = new ArrayList<>();
        for (LocalDate expiry : document.byExpiry().keySet()) {
            if (expiry.isAfter(asOf)) {
                expiries.add(expiry);
            }
        }
        return List.copyOf(expiries);
    }

    @Override
    public OptionChain getOptionChain(String symbol, LocalDate expiry) {
        if (expiry == null) {
            throw new IllegalArgumentException("expiry must not be null");
        }
        Document document = document(symbol);
        List<OptionQuote> quotes = document.byExpiry().get(expiry);
        if (quotes == null) {
            throw new IllegalArgumentException("Cboe lists no " + document.symbol() + " expiry on " + expiry
                    + "; listed: " + document.byExpiry().keySet());
        }
        return new OptionChain(document.symbol(), expiry, document.spot(), quotes);
    }

    @Override
    public SourcedChain getSourcedChain(String symbol, LocalDate expiry) {
        OptionChain chain = getOptionChain(symbol, expiry);
        String asOf = document(symbol).asOf();
        return new SourcedChain(chain, SOURCE, true,
                List.of("Cboe quotes as of " + (asOf == null ? "unknown time" : asOf + " UTC") + " (15-minute delayed)"),
                parseFeedTimestamp(asOf));
    }

    /** The feed's {@code timestamp} ("yyyy-MM-dd HH:mm:ss") is the document's generation time in UTC; null if absent or malformed. */
    static Instant parseFeedTimestamp(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(text.trim(), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .toInstant(java.time.ZoneOffset.UTC);
        } catch (java.time.format.DateTimeParseException malformed) {
            return null;
        }
    }

    private Document document(String symbol) {
        String sym = sanitize(symbol);
        Instant now = clock.instant();
        Document cached = cache.get(sym);
        if (cached != null && Duration.between(cached.fetchedAt(), now).compareTo(CACHE_TTL) < 0) {
            return cached;
        }
        String body;
        try {
            body = fetcher.get(String.format(URL_TEMPLATE, sym));
        } catch (IOException e) {
            throw new RuntimeException("Cboe option chain for " + sym + " unavailable: " + e.getMessage(), e);
        }
        Document fresh = parse(sym, body, now);
        cache.put(sym, fresh);
        return fresh;
    }

    static String sanitize(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        String sym = symbol.trim().toUpperCase(Locale.ROOT);
        if (!SYMBOL.matcher(sym).matches()) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return sym;
    }

    /** Parses the feed; malformed contracts are skipped, a document with no usable contract is an error. */
    static Document parse(String symbol, String body, Instant fetchedAt) {
        JsonNode root;
        try {
            root = JSON.readTree(body == null ? "" : body);
        } catch (IOException e) {
            throw new RuntimeException("Cboe response for " + symbol + " is not JSON", e);
        }
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isObject()) {
            throw new RuntimeException("Cboe response for " + symbol + " has no data section");
        }
        double spot = data.path("current_price").asDouble(Double.NaN);
        if (!(spot > 0.0)) {
            throw new RuntimeException("Cboe response for " + symbol + " has no current price");
        }
        String asOf = root.hasNonNull("timestamp") ? root.get("timestamp").asText() : null;

        Map<LocalDate, List<OptionQuote>> byExpiry = new TreeMap<>();
        for (JsonNode option : data.path("options")) {
            java.util.Optional<com.sbk.optionspricer.instruments.OccSymbol.Parsed> contract =
                    com.sbk.optionspricer.instruments.OccSymbol.parse(option.path("option").asText(""));
            if (contract.isEmpty()) {
                continue;
            }
            LocalDate expiry = contract.get().expiry();
            double strike = contract.get().strike();
            OptionType type = contract.get().type();
            double bid = option.path("bid").asDouble(Double.NaN);
            double ask = option.path("ask").asDouble(Double.NaN);
            if (!Double.isFinite(bid) || !Double.isFinite(ask) || bid < 0.0 || ask < 0.0) {
                continue;
            }
            double iv = Math.max(0.0, option.path("iv").asDouble(0.0));
            long volume = (long) Math.max(0.0, option.path("volume").asDouble(0.0));
            long openInterest = (long) Math.max(0.0, option.path("open_interest").asDouble(0.0));
            byExpiry.computeIfAbsent(expiry, e -> new ArrayList<>())
                    .add(new OptionQuote(symbol, expiry, strike, type, bid, ask, Double.isFinite(iv) ? iv : 0.0, volume, openInterest));
        }
        if (byExpiry.isEmpty()) {
            throw new RuntimeException("Cboe response for " + symbol + " contains no parseable option contracts");
        }
        Map<LocalDate, List<OptionQuote>> frozen = new TreeMap<>();
        byExpiry.forEach((expiry, quotes) -> frozen.put(expiry, List.copyOf(quotes)));
        return new Document(symbol, spot, asOf, java.util.Collections.unmodifiableMap(frozen), fetchedAt);
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static String httpGet(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("Accept", "application/json")
                .header("User-Agent", "options-pricing-engine")
                .GET()
                .build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Cboe returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching Cboe quotes", e);
        }
    }
}
