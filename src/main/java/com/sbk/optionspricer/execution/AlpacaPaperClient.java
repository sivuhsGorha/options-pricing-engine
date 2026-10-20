package com.sbk.optionspricer.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbk.optionspricer.config.EnvironmentConfigLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Thin client for the Alpaca Trading API, paper endpoint only. The base URL is a constant: no configuration
 * value or environment variable can point this class at a live account. Keys come from {@code .env}
 * ({@code ALPACA_KEY_ID}, {@code ALPACA_SECRET}) and are never logged; an error carries Alpaca's status code
 * and message only.
 *
 * <p>Quotes are read from the market-data host ({@link #DATA_BASE_URL}, read-only, the same keys) so an order can
 * be priced at the venue's own touch rather than at a lagging feed's print.
 *
 * <p>The HTTP layer is a small interface so tests run against recorded responses without a network.
 */
public final class AlpacaPaperClient {

    public static final String PAPER_BASE_URL = "https://paper-api.alpaca.markets";
    /** Market data (quotes). Stocks come from the IEX feed, the one a free data plan may read; options from the indicative feed. */
    public static final String DATA_BASE_URL = "https://data.alpaca.markets";
    public static final String KEY_ID_VARIABLE = "ALPACA_KEY_ID";
    public static final String SECRET_VARIABLE = "ALPACA_SECRET";

    public record Response(int status, String body) {}

    /** One HTTP exchange: method, path under the trading base URL, optional JSON body. */
    public interface Http {
        Response send(String method, String path, String jsonBody);

        /** A read from the market-data host; a test fake may answer it from the same script. */
        default Response data(String path) {
            return send("GET", path, null);
        }
    }

    /** A non-2xx answer from Alpaca, with its message (for example {@code qty must be > 0}). */
    public static final class AlpacaException extends RuntimeException {
        private final int status;

        public AlpacaException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public record Account(String status, String currency, double cash, double buyingPower, int optionsTradingLevel,
                          boolean tradingBlocked) {}

    /** A position at the venue; {@code signedQty} is negative for a short. */
    public record Position(String symbol, String assetClass, double signedQty, double avgEntryPrice) {}

    /** An order at the venue; {@code filledAvgPrice} is NaN until something has filled. */
    public record OrderState(String id, String clientOrderId, String symbol, String status, double qty, double filledQty,
                             double filledAvgPrice, List<LegFill> legs) {
        public OrderState(String id, String clientOrderId, String symbol, String status, double qty, double filledQty, double filledAvgPrice) {
            this(id, clientOrderId, symbol, status, qty, filledQty, filledAvgPrice, List.of());
        }

        public OrderState {
            legs = legs == null ? List.of() : List.copyOf(legs);
        }

        public boolean isWorking() {
            return switch (status) {
                case "new", "accepted", "pending_new", "accepted_for_bidding", "partially_filled", "pending_cancel",
                     "pending_replace", "calculated", "held" -> true;
                default -> false;
            };
        }
    }

    /** One leg of a multi-leg order as the venue reports it: its contract, and the average price it filled at (NaN until it has). */
    public record LegFill(String symbol, double filledQty, double filledAvgPrice) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Http http;

    /** Production client against the paper endpoint. */
    public AlpacaPaperClient(String keyId, String secret) {
        this(real(keyId, secret));
    }

    /** For tests: the HTTP layer is supplied. */
    AlpacaPaperClient(Http http) {
        if (http == null) {
            throw new IllegalArgumentException("http must not be null");
        }
        this.http = http;
    }

    /** Reads the keys from {@code .env}; fails with a message naming the variables when they are missing. */
    public static AlpacaPaperClient fromEnv() {
        String keyId = EnvironmentConfigLoader.get(KEY_ID_VARIABLE);
        String secret = EnvironmentConfigLoader.get(SECRET_VARIABLE);
        if (keyId == null || keyId.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalStateException("execution.transport is alpaca but " + KEY_ID_VARIABLE + " and " + SECRET_VARIABLE
                    + " are not both set in .env (paper keys from app.alpaca.markets, Paper Trading, API Keys)");
        }
        return new AlpacaPaperClient(keyId.trim(), secret.trim());
    }

    private static Http real(String keyId, String secret) {
        if (keyId == null || keyId.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Alpaca key id and secret must not be blank");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return new Http() {
            @Override
            public Response send(String method, String path, String jsonBody) {
                return exchange(client, keyId, secret, PAPER_BASE_URL + path, method, jsonBody);
            }

            @Override
            public Response data(String path) {
                return exchange(client, keyId, secret, DATA_BASE_URL + path, "GET", null);
            }
        };
    }

    private static Response exchange(HttpClient client, String keyId, String secret, String url, String method, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("APCA-API-KEY-ID", keyId)
                .header("APCA-API-SECRET-KEY", secret)
                .header("Accept", "application/json");
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while talking to Alpaca", e);
        }
    }

    // ------------------------------------------------------------------ endpoints

    public Account account() {
        JsonNode n = call("GET", "/v2/account", null);
        return new Account(text(n, "status"), text(n, "currency"), number(n, "cash"), number(n, "buying_power"),
                (int) number(n, "options_trading_level"), n.path("trading_blocked").asBoolean(false));
    }

    public boolean marketOpen() {
        return call("GET", "/v2/clock", null).path("is_open").asBoolean(false);
    }

    public List<Position> positions() {
        JsonNode array = call("GET", "/v2/positions", null);
        List<Position> out = new ArrayList<>();
        for (JsonNode n : array) {
            double qty = Math.abs(number(n, "qty"));
            if ("short".equals(text(n, "side"))) {
                qty = -qty;
            }
            out.add(new Position(text(n, "symbol"), text(n, "asset_class"), qty, number(n, "avg_entry_price")));
        }
        return List.copyOf(out);
    }

    /** A day limit order. Quantity is whole units; the price is sent with two decimals. */
    public OrderState submitLimitOrder(String symbol, boolean buy, int quantity, double limitPrice, String clientOrderId) {
        if (symbol == null || symbol.isBlank() || quantity <= 0 || !Double.isFinite(limitPrice) || limitPrice <= 0.0) {
            throw new IllegalArgumentException("symbol, a positive quantity and a positive limit price are required");
        }
        String body = String.format(Locale.ROOT,
                "{\"symbol\":\"%s\",\"qty\":\"%d\",\"side\":\"%s\",\"type\":\"limit\",\"time_in_force\":\"day\",\"limit_price\":\"%.2f\",\"client_order_id\":\"%s\"}",
                symbol.trim().toUpperCase(Locale.ROOT), quantity, buy ? "buy" : "sell", limitPrice, clientOrderId);
        return order(call("POST", "/v2/orders", body));
    }

    public record OptionLeg(String symbol, int ratioQty, boolean buy) {}

    /** A multi-leg complex limit order (e.g. straddle or spread) sent atomically to Alpaca. */
    public OrderState submitMultiLegOrder(List<OptionLeg> legs, int quantity, double limitPrice, String clientOrderId) {
        if (legs == null || legs.isEmpty() || quantity <= 0 || !Double.isFinite(limitPrice) || limitPrice <= 0.0) {
            throw new IllegalArgumentException("legs, a positive quantity and a positive limit price are required");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "{\"order_class\":\"mleg\",\"qty\":\"%d\",\"type\":\"limit\",\"time_in_force\":\"day\",\"limit_price\":\"%.2f\",\"client_order_id\":\"%s\",\"legs\":[",
                quantity, limitPrice, clientOrderId));
        for (int i = 0; i < legs.size(); i++) {
            OptionLeg leg = legs.get(i);
            if (i > 0) sb.append(",");
            // every example in Alpaca's multi-leg documentation names the intent; these orders only ever open
            sb.append(String.format(Locale.ROOT, "{\"symbol\":\"%s\",\"ratio_qty\":\"%d\",\"side\":\"%s\",\"position_intent\":\"%s\"}",
                    leg.symbol().trim().toUpperCase(Locale.ROOT), leg.ratioQty(), leg.buy() ? "buy" : "sell",
                    leg.buy() ? "buy_to_open" : "sell_to_open"));
        }
        sb.append("]}");
        return order(call("POST", "/v2/orders", sb.toString()));
    }

    public OrderState order(String id) {
        return order(call("GET", "/v2/orders/" + id, null));
    }

    /** Cancels a working order; an order that is already done is not an error. */
    public void cancel(String id) {
        Response r = http.send("DELETE", "/v2/orders/" + id, null);
        if (r.status() / 100 != 2 && r.status() != 404 && r.status() != 422) {
            throw new AlpacaException(r.status(), message(r));
        }
    }

    /** The venue's latest two-sided quote and its time. */
    public record Quote(double bid, double ask, java.time.Instant at) {}

    /**
     * The venue's latest quote: an option contract (OCC symbol) from the options indicative feed, anything else
     * as a stock from the IEX feed. A missing or one-sided quote is an error, never a zero.
     */
    public Quote latestQuote(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        String sym = symbol.trim().toUpperCase(Locale.ROOT);
        JsonNode q = com.sbk.optionspricer.instruments.OccSymbol.parse(sym).isPresent()
                ? callData("/v1beta1/options/quotes/latest?symbols=" + sym).path("quotes").path(sym)
                : callData("/v2/stocks/" + sym + "/quotes/latest?feed=iex").path("quote");
        if (q.isMissingNode() || !q.hasNonNull("bp") || !q.hasNonNull("ap") || !q.hasNonNull("t")) {
            throw new IllegalStateException("Alpaca returned no quote for " + sym);
        }
        return new Quote(number(q, "bp"), number(q, "ap"), java.time.Instant.parse(q.get("t").asText()));
    }

    /** Attempts to fetch the latest quote, returning empty when unavailable or on error. */
    public Optional<Quote> findQuote(String symbol) {
        try {
            return Optional.of(latestQuote(symbol));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Converts an Alpaca option quote to a standard MarketSnapshot.
     * Returns empty when the quote is one-sided, missing, or the symbol is not an OCC contract.
     */
    public static Optional<com.sbk.optionspricer.market.MarketSnapshot> toOptionSnapshot(
            String occSymbol, Quote quote, Clock clock) {
        if (occSymbol == null || quote == null || clock == null) {
            return Optional.empty();
        }
        var parsed = com.sbk.optionspricer.instruments.OccSymbol.parse(occSymbol);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        boolean twoSided = Double.isFinite(quote.bid()) && quote.bid() > 0.0 && Double.isFinite(quote.ask()) && quote.ask() > quote.bid();
        if (!twoSided) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Instant sourceTime = quote.at() != null ? quote.at() : now;
        long ageMs = Math.max(0L, now.toEpochMilli() - sourceTime.toEpochMilli());
        // DELAYED, not LIVE: the request names no feed, and the quotes this account gets come from Alpaca's indicative
        // options feed, which has not been shown to be the exchange's own real-time quote. Fresh enough to back an
        // order under the data-quality policy, but not claimed to be more than it is.
        var status = ageMs > com.sbk.optionspricer.market.OptionQuoteSnapshots.STALE_AFTER_MS
                ? com.sbk.optionspricer.market.MarketDataStatus.STALE
                : com.sbk.optionspricer.market.MarketDataStatus.DELAYED;
        var contract = parsed.get();
        com.sbk.optionspricer.instruments.Instrument instrument = new com.sbk.optionspricer.instruments.Instrument(
                contract.underlying(), contract.expiry(), contract.strike(), contract.type(),
                com.sbk.optionspricer.market.OptionQuoteSnapshots.STANDARD_EQUITY_MULTIPLIER, null, 0.01, true);
        double mid = (quote.bid() + quote.ask()) / 2.0;
        return Optional.of(new com.sbk.optionspricer.market.MarketSnapshot(
                occSymbol, quote.bid(), quote.ask(), mid,
                com.sbk.optionspricer.market.MarketSnapshot.VOLUME_UNKNOWN,
                now, sourceTime, ageMs, "ALPACA_INDICATIVE", status, instrument));
    }

    /** One trading day: its date (the bar's own, in New York) and its split-adjusted close. */
    public record DailyBar(java.time.LocalDate date, double close) {}

    /**
     * Daily bars from {@code start} on, oldest first, from the IEX feed (a free feed, so its closes can differ a
     * little from the consolidated close; fine for a volatility estimate). The last bar may be today's, still
     * forming. No bars at all is an error, never an empty series that would read as a calm market.
     */
    public List<DailyBar> dailyBars(String symbol, java.time.LocalDate start) {
        if (symbol == null || symbol.isBlank() || start == null) {
            throw new IllegalArgumentException("symbol and start are required");
        }
        String sym = symbol.trim().toUpperCase(Locale.ROOT);
        JsonNode bars = callData("/v2/stocks/" + sym + "/bars?timeframe=1Day&start=" + start
                + "&limit=1000&feed=iex&adjustment=split").path("bars");
        List<DailyBar> out = new ArrayList<>();
        if (bars.isArray()) {
            for (JsonNode bar : bars) {
                if (bar.hasNonNull("t") && bar.hasNonNull("c")) {
                    out.add(new DailyBar(java.time.LocalDate.ofInstant(java.time.Instant.parse(bar.get("t").asText()),
                            java.time.ZoneOffset.UTC), number(bar, "c")));
                }
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Alpaca returned no daily bars for " + sym);
        }
        out.sort(java.util.Comparator.comparing(DailyBar::date));
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ plumbing

    private JsonNode call(String method, String path, String body) {
        return parse(http.send(method, path, body), method, path);
    }

    private JsonNode callData(String path) {
        return parse(http.data(path), "GET", path);
    }

    private static JsonNode parse(Response r, String method, String path) {
        if (r.status() / 100 != 2) {
            throw new AlpacaException(r.status(), message(r));
        }
        try {
            return r.body() == null || r.body().isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(r.body());
        } catch (IOException e) {
            throw new IllegalStateException("Alpaca returned a body that is not JSON for " + method + " " + path, e);
        }
    }

    private static String message(Response r) {
        try {
            JsonNode n = MAPPER.readTree(r.body());
            if (n.hasNonNull("message")) {
                return "HTTP " + r.status() + ": " + n.get("message").asText();
            }
        } catch (IOException | RuntimeException ignored) {
            // not JSON: report the status alone
        }
        return "HTTP " + r.status();
    }

    private static OrderState order(JsonNode n) {
        List<LegFill> legs = new java.util.ArrayList<>();
        if (n.has("legs") && n.get("legs").isArray()) {
            for (JsonNode leg : n.get("legs")) {
                legs.add(new LegFill(text(leg, "symbol"), number(leg, "filled_qty"),
                        leg.hasNonNull("filled_avg_price") ? number(leg, "filled_avg_price") : Double.NaN));
            }
        }
        return new OrderState(text(n, "id"), text(n, "client_order_id"), text(n, "symbol"), text(n, "status"),
                number(n, "qty"), number(n, "filled_qty"), n.hasNonNull("filled_avg_price") ? number(n, "filled_avg_price") : Double.NaN, legs);
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : "";
    }

    private static double number(JsonNode n, String field) {
        if (!n.hasNonNull(field)) {
            return 0.0;
        }
        JsonNode v = n.get(field);
        if (v.isNumber()) {
            return v.asDouble();
        }
        try {
            return Double.parseDouble(v.asText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Alpaca field " + field + " is not a number: " + v.asText());
        }
    }
}
