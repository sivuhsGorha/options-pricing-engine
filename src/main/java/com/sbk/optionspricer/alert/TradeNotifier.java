package com.sbk.optionspricer.alert;

import com.sbk.optionspricer.execution.VolSpreadStrategy.Decision;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Tells the phone when the strategy sent orders: what it did, and each order with how it ended. A hold or a wait is not a trade and sends
 * nothing. The message is sent from a worker thread of its own, so a slow or failing phone service can neither delay the next decision nor
 * break the one being recorded; a failure is logged, without the topic or the URL.
 */
public final class TradeNotifier implements Consumer<Decision>, AutoCloseable {

    private static final int MAX_MESSAGE = 700;

    private final Notifier notifier;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "alert-trade-notice");
        t.setDaemon(true);
        return t;
    });

    public TradeNotifier(Notifier notifier) {
        if (notifier == null) {
            throw new IllegalArgumentException("notifier must not be null");
        }
        this.notifier = notifier;
    }

    @Override
    public void accept(Decision decision) {
        if (decision == null || decision.orders() == null || decision.orders().isEmpty()) {
            return;
        }
        String orders = String.join("; ", decision.orders());
        boolean refused = decision.action().toUpperCase(Locale.ROOT).contains("REJECTED") || orders.toLowerCase(Locale.ROOT).contains("rejected");
        String message = decision.summary() + "\n" + orders;
        String text = message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) + "..." : message;
        String title = "TRADE: " + decision.action();
        String tags = refused ? "warning" : "moneybag";
        try {
            worker.execute(() -> {
                try {
                    notifier.send(title, text, "default", tags);
                } catch (RuntimeException e) {
                    System.err.println("[ALERT] trade notice not delivered: " + e.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException closed) {
            // shut down: nothing to tell
        }
    }

    @Override
    public void close() {
        worker.shutdown();
    }
}
