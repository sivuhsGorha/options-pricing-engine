package com.sbk.optionspricer.core;

import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionChainProvider;
import com.sbk.optionspricer.volatility.SurfaceFitter;
import com.sbk.optionspricer.volatility.VolatilitySurfaceSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Loads option chains for a few expiries and fits SSVI and SABR surfaces to them, on its own thread, on a
 * schedule. The dashboard reads {@link #latest()} and {@link #status()}; nothing on the startup path waits for
 * a network call. Every snapshot records which provider produced the chains, so a synthetic fallback is
 * labelled as such and never shown as a market-fitted surface.
 */
public final class VolatilitySurfaceService implements VolatilitySurfaceSource, AutoCloseable {

    /** Target tenors in days; the listed expiry nearest to each is used, without duplicates. */
    static final int[] TARGET_TENOR_DAYS = {30, 60, 90, 180};
    static final int MIN_DAYS_TO_EXPIRY = 7;

    private final OptionChainProvider provider;
    private final String symbol;
    private final double riskFreeRate;
    private final double dividendYield;
    private final Duration refreshInterval;
    private final Clock clock;
    private final Consumer<OptionChain> onChainLoaded;

    private volatile Status status;
    private volatile Snapshot latest;
    private Thread worker;
    private volatile boolean closed;

    public VolatilitySurfaceService(OptionChainProvider provider, String symbol, double riskFreeRate, double dividendYield,
                                    Duration refreshInterval, Clock clock, Consumer<OptionChain> onChainLoaded) {
        if (provider == null || symbol == null || symbol.isBlank() || refreshInterval == null || clock == null) {
            throw new IllegalArgumentException("provider, symbol, refreshInterval and clock must not be null");
        }
        if (refreshInterval.isNegative() || refreshInterval.isZero()) {
            throw new IllegalArgumentException("refreshInterval must be positive");
        }
        this.provider = provider;
        this.symbol = symbol.trim().toUpperCase(java.util.Locale.ROOT);
        this.riskFreeRate = riskFreeRate;
        this.dividendYield = dividendYield;
        this.refreshInterval = refreshInterval;
        this.clock = clock;
        this.onChainLoaded = onChainLoaded == null ? chain -> { } : onChainLoaded;
        this.status = new Status(State.LOADING, "calibration has not run yet", clock.instant());
    }

    /** Runs one calibration now, on the calling thread. Safe to call from tests and from the worker. */
    public synchronized void refresh() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Set<String> warnings = new LinkedHashSet<>(); // a provider failing on every expiry is reported once

        List<LocalDate> expiries;
        try {
            expiries = selectExpiries(provider.listExpiries(symbol, today), today);
        } catch (RuntimeException e) {
            warnings.add("expiry listing failed (" + e.getMessage() + "); using monthly expiries");
            expiries = selectExpiries(OptionChainProvider.thirdFridays(today, 4), today);
        }

        List<OptionChain> chains = new ArrayList<>();
        Set<String> sources = new LinkedHashSet<>();
        boolean marketData = true;
        for (LocalDate expiry : expiries) {
            try {
                OptionChainProvider.SourcedChain sourced = provider.getSourcedChain(symbol, expiry);
                chains.add(sourced.chain());
                sources.add(sourced.source());
                marketData &= sourced.marketData();
                warnings.addAll(sourced.notes());
                onChainLoaded.accept(sourced.chain());
            } catch (RuntimeException e) {
                warnings.add("expiry " + expiry + ": " + rootMessage(e));
            }
        }
        if (chains.isEmpty()) {
            status = new Status(State.FAILED, "no option chain for " + symbol + (warnings.isEmpty() ? "" : ": " + warnings.iterator().next()), now);
            return;
        }

        SurfaceFitter.Extraction extraction = SurfaceFitter.extractPoints(chains, riskFreeRate, dividendYield, today);
        warnings.addAll(extraction.warnings());
        SurfaceFitter.Fit ssvi = tryFit("SSVI", () -> SurfaceFitter.fitSsvi(extraction.points()), warnings);
        SurfaceFitter.Fit sabr = tryFit("SABR", () -> SurfaceFitter.fitSabr(extraction.points()), warnings);
        if (ssvi == null && sabr == null) {
            status = new Status(State.FAILED, "could not fit a surface to " + extraction.points().size() + " quotes: " + lastOrEmpty(warnings), now);
            return;
        }
        latest = new Snapshot(now, symbol, String.join("+", sources), marketData, chains.get(0).spot(), ssvi, sabr,
                extraction.quotesSkipped(), List.copyOf(warnings));
        status = new Status(State.READY, String.format(java.util.Locale.ROOT, "%d quotes from %s", extraction.points().size(), String.join("+", sources)), now);
    }

    /** Starts the background worker: calibrate now, then every refresh interval, until {@link #close()}. */
    public synchronized void start() {
        if (worker != null) {
            return;
        }
        worker = new Thread(() -> {
            while (!closed) {
                try {
                    refresh();
                } catch (Throwable t) {
                    status = new Status(State.FAILED, "calibration failed: " + rootMessage(t), clock.instant());
                }
                try {
                    Thread.sleep(refreshInterval.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "vol-surface-calibration");
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

    @Override
    public Optional<Snapshot> latest() {
        return Optional.ofNullable(latest);
    }

    @Override
    public Status status() {
        return status;
    }

    /** For each target tenor, the listed expiry nearest to it that is at least MIN_DAYS_TO_EXPIRY away; no duplicates. */
    static List<LocalDate> selectExpiries(List<LocalDate> listed, LocalDate today) {
        List<LocalDate> eligible = listed.stream()
                .filter(d -> !d.isBefore(today.plusDays(MIN_DAYS_TO_EXPIRY)))
                .sorted()
                .toList();
        Set<LocalDate> chosen = new LinkedHashSet<>();
        for (int tenor : TARGET_TENOR_DAYS) {
            LocalDate target = today.plusDays(tenor);
            LocalDate best = null;
            for (LocalDate d : eligible) {
                if (best == null || Math.abs(d.toEpochDay() - target.toEpochDay()) < Math.abs(best.toEpochDay() - target.toEpochDay())) {
                    best = d;
                }
            }
            if (best != null) {
                chosen.add(best);
            }
        }
        return new ArrayList<>(chosen).stream().sorted().toList();
    }

    private static SurfaceFitter.Fit tryFit(String model, Supplier<SurfaceFitter.Fit> fit, Set<String> warnings) {
        try {
            return fit.get();
        } catch (RuntimeException e) {
            warnings.add(model + " not fitted: " + e.getMessage());
            return null;
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static String lastOrEmpty(Set<String> warnings) {
        String last = "";
        for (String w : warnings) last = w;
        return last;
    }
}
