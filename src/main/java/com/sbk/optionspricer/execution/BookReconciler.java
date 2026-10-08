package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.risk.PortfolioPosition;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares the local book with the positions the Alpaca paper account reports, at start and on a schedule.
 * Any difference trips the trading halt with a reason that names each position and both quantities, because
 * an order gate that checks limits against a book that is not the venue's is checking the wrong book. The
 * halt is tripped again if an operator resumes while the difference persists. An account that cannot be
 * reached is reported, not treated as a mismatch.
 */
public final class BookReconciler implements AutoCloseable {

    /** @param status {@code OK}, {@code MISMATCH} or {@code UNREACHABLE} */
    public record Result(Instant at, String status, List<String> differences, String message) {}

    private final AlpacaPaperClient client;
    private final PositionTracker tracker;
    private final TradingHalt halt;
    private final Duration interval;
    private final Clock clock;

    private volatile Result last;
    private String haltedFor;
    private Thread worker;
    private volatile boolean closed;

    public BookReconciler(AlpacaPaperClient client, PositionTracker tracker, TradingHalt halt, Duration interval, Clock clock) {
        if (client == null || tracker == null || halt == null || interval == null || interval.isNegative() || interval.isZero() || clock == null) {
            throw new IllegalArgumentException("client, tracker, halt, a positive interval and clock are required");
        }
        this.client = client;
        this.tracker = tracker;
        this.halt = halt;
        this.interval = interval;
        this.clock = clock;
    }

    /** Reconciles now, on the calling thread. */
    public synchronized Result reconcile() {
        Instant now = clock.instant();
        Map<String, Integer> local = new TreeMap<>();
        for (Map.Entry<String, PortfolioPosition> e : tracker.getPositions().entrySet()) {
            if (e.getValue().getQuantity() != 0) {
                local.put(e.getKey(), e.getValue().getQuantity());
            }
        }
        Map<String, Double> remote = new TreeMap<>();
        try {
            for (AlpacaPaperClient.Position p : client.positions()) {
                remote.merge(p.symbol().toUpperCase(Locale.ROOT), p.signedQty(), Double::sum);
            }
        } catch (RuntimeException e) {
            last = new Result(now, "UNREACHABLE", List.of(), "Alpaca positions unavailable: " + rootMessage(e));
            return last;
        }
        List<String> differences = new ArrayList<>();
        TreeSet<String> symbols = new TreeSet<>(local.keySet());
        symbols.addAll(remote.keySet());
        for (String symbol : symbols) {
            int here = local.getOrDefault(symbol, 0);
            double there = remote.getOrDefault(symbol, 0.0);
            if (Math.abs(here - there) > 1e-9) {
                differences.add(symbol + ": local " + here + ", alpaca " + quantity(there));
            }
        }
        if (differences.isEmpty()) {
            haltedFor = null;
            last = new Result(now, "OK", List.of(), "book matches the Alpaca account (" + local.size() + " open positions)");
            return last;
        }
        String reason = "book does not match the Alpaca account: " + String.join("; ", differences);
        if (!reason.equals(haltedFor) || !halt.isHalted()) {
            halt.halt(reason);
            haltedFor = reason;
        }
        last = new Result(now, "MISMATCH", List.copyOf(differences), reason);
        return last;
    }

    /** Reconciles now, then every interval, until {@link #close()}. */
    public synchronized void start() {
        if (worker != null) {
            return;
        }
        worker = new Thread(() -> {
            while (!closed) {
                try {
                    reconcile();
                } catch (Throwable t) {
                    last = new Result(clock.instant(), "UNREACHABLE", List.of(), "reconciliation failed: " + rootMessage(t));
                }
                try {
                    Thread.sleep(interval.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "book-reconciliation");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void close() {
        closed = true;
        Thread w;
        synchronized (this) {
            w = worker;
        }
        if (w != null) {
            w.interrupt();
        }
    }

    /** The latest result, or null before the first run. */
    public Result lastResult() {
        return last;
    }

    private static String quantity(double q) {
        return q == Math.rint(q) ? Long.toString((long) q) : Double.toString(q);
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
