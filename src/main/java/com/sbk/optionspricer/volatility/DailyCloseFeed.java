package com.sbk.optionspricer.volatility;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Keeps a {@link RealizedVolForecast} supplied with one close per trading day, which is the unit it is built for
 * (feeding it live ticks would annualise tick-sized moves as if they were daily). The closes come from a source
 * function (the broker's daily bars in the application); today's bar is dropped because, while the market is open,
 * its close is only the latest print. A failed load keeps what the forecast already has and records why, so the
 * dashboard-side readout simply goes quiet instead of showing a number built on nothing.
 */
public final class DailyCloseFeed implements AutoCloseable {

    /** One trading day's date and close. */
    public record DailyClose(LocalDate date, double close) {}

    /** Fewest completed days worth replacing the history with. */
    static final int MIN_CLOSES = RealizedVolForecast.MIN_EWMA_RETURNS + 1;
    /** How far back to ask: about 250 trading days. */
    private static final int LOOKBACK_DAYS = 400;
    private static final int KEEP_CLOSES = 250;
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(10);

    private final RealizedVolForecast target;
    private final Function<LocalDate, List<DailyClose>> source;
    private final Clock clock;
    private final Duration every;
    private volatile String lastProblem = "not loaded yet";
    private Thread worker;
    private volatile boolean closed;

    public DailyCloseFeed(RealizedVolForecast target, Function<LocalDate, List<DailyClose>> source, Clock clock, Duration every) {
        if (target == null || source == null || clock == null || every == null) {
            throw new IllegalArgumentException("target, source, clock and every must not be null");
        }
        this.target = target;
        this.source = source;
        this.clock = clock;
        this.every = every;
    }

    /** Why the last load did not replace the history; empty when it did. */
    public String lastProblem() {
        return lastProblem;
    }

    /** One load. Returns whether the forecast now holds a fresh series; never throws. */
    public boolean refreshOnce() {
        try {
            LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            List<DailyClose> bars = source.apply(today.minusDays(LOOKBACK_DAYS));
            List<DailyClose> completed = bars.stream()
                    .filter(b -> b.date().isBefore(today) && Double.isFinite(b.close()) && b.close() > 0.0)
                    .sorted(Comparator.comparing(DailyClose::date))
                    .toList();
            if (completed.size() < MIN_CLOSES) {
                lastProblem = "only " + completed.size() + " completed daily closes; keeping the previous history";
                return false;
            }
            List<DailyClose> kept = completed.subList(Math.max(0, completed.size() - KEEP_CLOSES), completed.size());
            target.setPrices(kept.stream().mapToDouble(DailyClose::close).toArray());
            lastProblem = "";
            return true;
        } catch (RuntimeException e) {
            lastProblem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return false;
        }
    }

    /** Loads now and then every {@code every}; after a failure it retries in ten minutes. */
    public synchronized void start() {
        if (worker != null || closed) {
            return;
        }
        worker = new Thread(() -> {
            while (!closed) {
                boolean ok = refreshOnce();
                System.out.println(ok
                        ? "[FAIR VOL] " + target.historySize() + " daily closes loaded"
                        : "[FAIR VOL] daily closes not loaded: " + lastProblem);
                try {
                    Thread.sleep((ok ? every : RETRY_AFTER_FAILURE).toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "daily-close-feed");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (worker != null) {
            worker.interrupt();
        }
    }
}
