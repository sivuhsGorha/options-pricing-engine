package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.Main;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code --test-buy-straddle} sends an order straight through the Alpaca client. The engine's ledger never sees it, so the
 * reconciler finds a position it does not know about and halts trading: running it must be a choice, not an accident.
 */
class BuyStraddleCommandTest {

    private final List<String> orders = new ArrayList<>();

    private AlpacaPaperClient client() {
        return new AlpacaPaperClient(new AlpacaPaperClient.Http() {
            @Override public AlpacaPaperClient.Response send(String method, String path, String body) {
                if ("GET".equals(method) && "/v2/account".equals(path)) {
                    return new AlpacaPaperClient.Response(200,
                            "{\"status\":\"ACTIVE\",\"currency\":\"USD\",\"cash\":\"1000\",\"buying_power\":\"2000\",\"options_trading_level\":3,\"trading_blocked\":false}");
                }
                if ("POST".equals(method) && "/v2/orders".equals(path)) {
                    orders.add(body);
                    return new AlpacaPaperClient.Response(200, FakeAlpaca.orderJson("order-1", "new", 1, 0, null, "", "buy"));
                }
                throw new IllegalStateException("unexpected " + method + " " + path);
            }

            @Override public AlpacaPaperClient.Response data(String path) {
                if (path.contains("/v2/stocks/SPY/quotes/latest")) {
                    return new AlpacaPaperClient.Response(200, FakeAlpaca.stockQuoteJson("SPY", 499.90, 500.10, "2026-10-15T14:00:00Z"));
                }
                String symbol = path.substring(path.indexOf("symbols=") + "symbols=".length());
                return new AlpacaPaperClient.Response(200, FakeAlpaca.optionQuoteJson(symbol, 4.90, 5.00, "2026-10-15T14:00:00Z"));
            }
        });
    }

    private String run(boolean confirm, int[] exit) {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            exit[0] = Main.runTestBuyStraddle(client(), confirm);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void withoutConfirmItShowsTheOrderItWouldSendAndSendsNothing() {
        int[] exit = new int[1];

        String text = run(false, exit);

        assertEquals(0, exit[0], text);
        assertTrue(orders.isEmpty(), "a dry run must not place an order: " + orders);
        assertTrue(text.contains("DRY RUN"), text);
        assertTrue(text.contains("--confirm"), text);
    }

    @Test
    void withConfirmItSendsOneMultiLegBuyAndSaysPlainlyThatTheLedgerWillNotKnowAboutIt() {
        int[] exit = new int[1];

        String text = run(true, exit);

        assertEquals(0, exit[0], text);
        assertEquals(1, orders.size());
        assertTrue(orders.get(0).contains("\"order_class\":\"mleg\""), orders.get(0));
        assertEquals(2, orders.get(0).split("\"position_intent\":\"buy_to_open\"", -1).length - 1, orders.get(0));
        assertTrue(text.toLowerCase().contains("reconcil") && text.toLowerCase().contains("ledger"), text);
    }
}
