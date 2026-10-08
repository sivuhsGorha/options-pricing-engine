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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Thin client for the Alpaca Trading API, paper endpoint only. The base URL is a constant: no configuration
 * value or environment variable can point this class at a live account. Keys come from {@code .env}
 * ({@code ALPACA_KEY_ID}, {@code ALPACA_SECRET}) and are never logged; an error carries Alpaca's status code
 * and message only.
 *
 * <p>The HTTP layer is a one-method interface so tests run against recorded responses without a network.
 */
public final class AlpacaPaperClient {

    public static final String PAPER_BASE_URL = "https://paper-api.alpaca.markets";
    public static final String KEY_ID_VARIABLE = "ALPACA_KEY_ID";
    public static final String SECRET_VARIABLE = "ALPACA_SECRET";

    public record Response(int status, String body) {}

    /** One HTTP exchange: method, path under the base URL, optional JSON body. */
    @FunctionalInterface
    public interface Http {
        Response send(String method, String path, String jsonBody);
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
                             double filledAvgPrice) {
        public boolean isWorking() {
            return switch (status) {
                case "new", "accepted", "pending_new", "accepted_for_bidding", "partially_filled", "pending_cancel",
                     "pending_replace", "calculated", "held" -> true;
                default -> false;
            };
        }
    }

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
        return (method, path, jsonBody) -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(PAPER_BASE_URL + path))
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
        };
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

    // ------------------------------------------------------------------ plumbing

    private JsonNode call(String method, String path, String body) {
        Response r = http.send(method, path, body);
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
        return new OrderState(text(n, "id"), text(n, "client_order_id"), text(n, "symbol"), text(n, "status"),
                number(n, "qty"), number(n, "filled_qty"), n.hasNonNull("filled_avg_price") ? number(n, "filled_avg_price") : Double.NaN);
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
