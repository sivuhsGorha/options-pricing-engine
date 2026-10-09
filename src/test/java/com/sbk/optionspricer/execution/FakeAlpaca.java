package com.sbk.optionspricer.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** A scripted Alpaca paper API: recorded JSON per endpoint, every request remembered. No network. */
final class FakeAlpaca implements AlpacaPaperClient.Http {

    /** Requests to the trading host, in order. */
    final List<String> calls = new ArrayList<>();
    /** Requests to the market-data host (quotes), in order; kept apart because the real client uses two hosts. */
    final List<String> dataCalls = new ArrayList<>();
    private Supplier<AlpacaPaperClient.Response> onPost = () -> new AlpacaPaperClient.Response(500, "{\"message\":\"no scripted POST\"}");
    /** Without a scripted quote the data host answers 404, and the transport falls back to the feed's book. */
    private Supplier<AlpacaPaperClient.Response> onQuote = () -> new AlpacaPaperClient.Response(404, "{\"message\":\"no scripted quote\"}");
    private final List<AlpacaPaperClient.Response> onGetOrder = new ArrayList<>();
    private Supplier<AlpacaPaperClient.Response> onDelete = () -> new AlpacaPaperClient.Response(204, "");
    private Supplier<AlpacaPaperClient.Response> onPositions = () -> new AlpacaPaperClient.Response(200, "[]");
    private int getIndex;

    /** The order JSON Alpaca returns, in the shape recorded from the paper API on 2026-10-08. */
    static String orderJson(String id, String status, int qty, double filledQty, Double filledAvgPrice, String symbol, String side) {
        return String.format(Locale.ROOT,
                "{\"id\":\"%s\",\"client_order_id\":\"aura-1\",\"created_at\":\"2026-10-08T08:02:21.992576018Z\",\"filled_at\":null,"
                        + "\"asset_id\":\"b28f4066\",\"symbol\":\"%s\",\"asset_class\":\"us_equity\",\"notional\":null,\"qty\":\"%d\","
                        + "\"filled_qty\":\"%s\",\"filled_avg_price\":%s,\"order_class\":\"\",\"order_type\":\"limit\",\"type\":\"limit\","
                        + "\"side\":\"%s\",\"position_intent\":\"buy_to_open\",\"time_in_force\":\"day\",\"limit_price\":\"500\","
                        + "\"stop_price\":null,\"status\":\"%s\",\"extended_hours\":false,\"legs\":null}",
                id, symbol, qty, filledQty == Math.rint(filledQty) ? Long.toString((long) filledQty) : Double.toString(filledQty),
                filledAvgPrice == null ? "null" : "\"" + filledAvgPrice + "\"", side, status);
    }

    /** The latest stock quote, in the shape recorded from data.alpaca.markets (IEX feed) on 2026-10-09. */
    static String stockQuoteJson(String symbol, double bid, double ask, String time) {
        return String.format(Locale.ROOT,
                "{\"quote\":{\"ap\":%s,\"as\":160,\"ax\":\"V\",\"bp\":%s,\"bs\":240,\"bx\":\"V\",\"c\":[\"R\"],\"t\":\"%s\",\"z\":\"B\"},\"symbol\":\"%s\"}",
                ask, bid, time, symbol);
    }

    /** The latest option quote, in the shape recorded from data.alpaca.markets (indicative feed) on 2026-10-09. */
    static String optionQuoteJson(String symbol, double bid, double ask, String time) {
        return String.format(Locale.ROOT,
                "{\"quotes\":{\"%s\":{\"ap\":%s,\"as\":76,\"ax\":\"Q\",\"bp\":%s,\"bs\":41,\"bx\":\"Q\",\"c\":\" \",\"t\":\"%s\"}}}",
                symbol, ask, bid, time);
    }

    FakeAlpaca quote(String body) {
        onQuote = () -> new AlpacaPaperClient.Response(200, body);
        return this;
    }

    FakeAlpaca quoteThrows(RuntimeException e) {
        onQuote = () -> { throw e; };
        return this;
    }

    static String positionJson(String symbol, String side, String qty, String assetClass, String avgEntry) {
        return String.format(Locale.ROOT,
                "{\"asset_id\":\"x\",\"symbol\":\"%s\",\"exchange\":\"ARCA\",\"asset_class\":\"%s\",\"avg_entry_price\":\"%s\",\"qty\":\"%s\","
                        + "\"side\":\"%s\",\"market_value\":\"0\",\"cost_basis\":\"0\",\"unrealized_pl\":\"0\",\"current_price\":\"0\",\"qty_available\":\"%s\"}",
                symbol, assetClass, avgEntry, qty, side, qty);
    }

    FakeAlpaca post(int status, String body) {
        onPost = () -> new AlpacaPaperClient.Response(status, body);
        return this;
    }

    FakeAlpaca postThrows(RuntimeException e) {
        onPost = () -> { throw e; };
        return this;
    }

    /** Successive answers to GET /v2/orders/{id}; the last one repeats. */
    FakeAlpaca getOrder(String... bodies) {
        for (String b : bodies) onGetOrder.add(new AlpacaPaperClient.Response(200, b));
        return this;
    }

    FakeAlpaca delete(int status, String body) {
        onDelete = () -> new AlpacaPaperClient.Response(status, body);
        return this;
    }

    FakeAlpaca deleteThrows(RuntimeException e) {
        onDelete = () -> { throw e; };
        return this;
    }

    FakeAlpaca positions(String... rows) {
        String body = "[" + String.join(",", rows) + "]";
        onPositions = () -> new AlpacaPaperClient.Response(200, body);
        return this;
    }

    FakeAlpaca positionsThrow(RuntimeException e) {
        onPositions = () -> { throw e; };
        return this;
    }

    @Override
    public AlpacaPaperClient.Response send(String method, String path, String jsonBody) {
        calls.add(method + " " + path + (jsonBody == null ? "" : " " + jsonBody));
        if ("POST".equals(method) && "/v2/orders".equals(path)) return onPost.get();
        if ("GET".equals(method) && path.startsWith("/v2/orders/")) {
            if (onGetOrder.isEmpty()) throw new IllegalStateException("no scripted GET order response");
            AlpacaPaperClient.Response r = onGetOrder.get(Math.min(getIndex, onGetOrder.size() - 1));
            getIndex++;
            return r;
        }
        if ("DELETE".equals(method) && path.startsWith("/v2/orders/")) return onDelete.get();
        if ("GET".equals(method) && "/v2/positions".equals(path)) return onPositions.get();
        throw new IllegalStateException("unexpected request " + method + " " + path);
    }

    @Override
    public AlpacaPaperClient.Response data(String path) {
        dataCalls.add("GET " + path);
        return onQuote.get();
    }

    long count(String prefix) {
        return calls.stream().filter(c -> c.startsWith(prefix)).count();
    }
}
