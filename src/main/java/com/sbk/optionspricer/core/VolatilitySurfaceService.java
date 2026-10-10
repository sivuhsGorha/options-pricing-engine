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
public final class VolatilitySurfaceService implements VolatilitySurfaceSource, com.sbk.optionspricer.market.OptionMarketData, AutoCloseable {

    /** After a failed refresh or a synthetic fallback the worker retries this soon, not after the full interval. */
    static final Duration RETRY_WHEN_DEGRADED = Duration.ofSeconds(60);
    /**
     * How often the option chains are reloaded between calibrations. The strategy judges a quote STALE 120 s after
     * the feed's timestamp, so chains loaded only at each 15-minute calibration were tradable for about two
     * minutes in fifteen (observed live on 2026-10-09: the vol-spread strategy waited on STALE quotes all morning).
     */
    public static final Duration DEFAULT_QUOTE_REFRESH = Duration.ofSeconds(60);

    /** A market-data surface exists and the last calibration succeeded. */
    boolean healthy() {
        Snapshot current = latest;
        return status.state() == State.READY && current != null && current.marketData();
    }

    /**
     * How long the worker waits before its next tick: the quote-refresh interval, or sooner while there is no
     * market-data surface to retry the calibration; never longer than the calibration interval.
     */
    Duration nextDelay() {
        Duration calibration = healthy() ? refreshInterval : min(refreshInterval, RETRY_WHEN_DEGRADED);
        return min(calibration, quoteRefreshInterval);
    }

    /** A calibration is due before the first one, every refresh interval after a healthy one, and at the retry cadence otherwise. */
    boolean fitDue(Instant now) {
        Instant last = lastFitAt;
        if (last == null) {
            return true;
        }
        Duration since = Duration.between(last, now);
        return healthy() ? since.compareTo(refreshInterval) >= 0 : since.compareTo(min(refreshInterval, RETRY_WHEN_DEGRADED)) >= 0;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** Target tenors in days; the listed expiry nearest to each is used, without duplicates. */
    static final int[] TARGET_TENOR_DAYS = {30, 60, 90, 180};
    static final int MIN_DAYS_TO_EXPIRY = 7;

    private final OptionChainProvider provider;
    private final String symbol;
    private final double riskFreeRate;
    private final double dividendYield;
    private final Duration refreshInterval;
    private final Duration quoteRefreshInterval;
    private final Clock clock;
    private final Consumer<OptionChain> onChainLoaded;

    private volatile Status status;
    private volatile Snapshot latest;
    /** When the last calibration was attempted, and when the chains were last loaded (by a calibration or a quote refresh). */
    private volatile Instant lastFitAt;
    private volatile Instant lastQuoteRefreshAt;
    /** The chains behind the latest snapshot, with their provenance and as-of time; the option market data. */
    private volatile List<OptionChainProvider.SourcedChain> latestChains = List.of();
    private Thread worker;
    private volatile boolean closed;
    private volatile Consumer<Snapshot> snapshotListener = snapshot -> { };

    /** Calibrates every {@code refreshInterval} and reloads the chains every {@link #DEFAULT_QUOTE_REFRESH}. */
    public VolatilitySurfaceService(OptionChainProvider provider, String symbol, double riskFreeRate, double dividendYield,
                                    Duration refreshInterval, Clock clock, Consumer<OptionChain> onChainLoaded) {
        this(provider, symbol, riskFreeRate, dividendYield, refreshInterval, DEFAULT_QUOTE_REFRESH, clock, onChainLoaded);
    }

    /**
     * @param refreshInterval      how often the surfaces are refitted
     * @param quoteRefreshInterval how often the chains are reloaded between refits (capped at {@code refreshInterval})
     */
    public VolatilitySurfaceService(OptionChainProvider provider, String symbol, double riskFreeRate, double dividendYield,
                                    Duration refreshInterval, Duration quoteRefreshInterval, Clock clock, Consumer<OptionChain> onChainLoaded) {
        if (provider == null || symbol == null || symbol.isBlank() || refreshInterval == null || quoteRefreshInterval == null || clock == null) {
            throw new IllegalArgumentException("provider, symbol, refreshInterval, quoteRefreshInterval and clock must not be null");
        }
        if (refreshInterval.isNegative() || refreshInterval.isZero() || quoteRefreshInterval.isNegative() || quoteRefreshInterval.isZero()) {
            throw new IllegalArgumentException("refreshInterval and quoteRefreshInterval must be positive");
        }
        this.provider = provider;
        this.symbol = symbol.trim().toUpperCase(java.util.Locale.ROOT);
        this.riskFreeRate = riskFreeRate;
        this.dividendYield = dividendYield;
        this.refreshInterval = refreshInterval;
        this.quoteRefreshInterval = min(quoteRefreshInterval, refreshInterval);
        this.clock = clock;
        this.onChainLoaded = onChainLoaded == null ? chain -> { } : onChainLoaded;
        this.status = new Status(State.LOADING, "calibration has not run yet", clock.instant());
    }

    /** Receives every successful calibration (for the surface history). A listener failure is logged, never fatal. */
    public void setSnapshotListener(Consumer<Snapshot> listener) {
        this.snapshotListener = listener == null ? snapshot -> { } : listener;
    }

    /** What one pass over the provider produced; {@code chains} is empty when nothing loaded. */
    private record Loaded(List<OptionChain> chains, List<OptionChainProvider.SourcedChain> sourced, Set<String> sources, boolean marketData) {}

    /**
     * Reloads the option chains without refitting, so the quotes the strategy trades on stay within their
     * freshness window between calibrations. A failed reload keeps the last chains (which then age into STALE
     * and the strategy waits) and leaves the surface and its status alone. Returns whether anything loaded.
     */
    public synchronized boolean refreshQuotes() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Set<String> warnings = new LinkedHashSet<>();
        Loaded loaded;
        try {
            loaded = load(today, warnings, false);
        } catch (RuntimeException e) {
            warnings.add(rootMessage(e));
            loaded = new Loaded(List.of(), List.of(), Set.of(), false);
        }
        if (loaded.chains().isEmpty()) {
            System.err.println("[SURFACE] quote refresh failed, keeping the last chains: " + lastOrEmpty(warnings));
            return false;
        }
        latestChains = List.copyOf(loaded.sourced());
        lastQuoteRefreshAt = now;
        return true;
    }

    /** One worker tick: a calibration when one is due, otherwise a quote refresh. Never throws. */
    void runOnce() {
        try {
            if (fitDue(clock.instant())) {
                refresh();
            } else {
                refreshQuotes();
            }
        } catch (Throwable t) {
            status = new Status(State.FAILED, "calibration failed: " + rootMessage(t), clock.instant());
        }
    }

    /** When the chains were last loaded, by a calibration or a quote refresh; empty before the first. */
    public Optional<Instant> lastQuoteRefreshAt() {
        return Optional.ofNullable(lastQuoteRefreshAt);
    }

    /** One pass over the provider: the expiries nearest the target tenors, each chain with its provenance. */
    private Loaded load(LocalDate today, Set<String> warnings, boolean recordHistory) {
        List<LocalDate> expiries;
        try {
            expiries = selectExpiries(provider.listExpiries(symbol, today), today);
        } catch (RuntimeException e) {
            warnings.add("expiry listing failed (" + e.getMessage() + "); using monthly expiries");
            expiries = selectExpiries(OptionChainProvider.thirdFridays(today, 4), today);
        }

        List<OptionChain> chains = new ArrayList<>();
        List<OptionChainProvider.SourcedChain> sourcedChains = new ArrayList<>();
        Set<String> sources = new LinkedHashSet<>();
        boolean marketData = true;
        for (LocalDate expiry : expiries) {
            try {
                OptionChainProvider.SourcedChain sourced = provider.getSourcedChain(symbol, expiry);
                chains.add(sourced.chain());
                sourcedChains.add(sourced);
                sources.add(sourced.source());
                marketData &= sourced.marketData();
                warnings.addAll(sourced.notes());
                if (recordHistory) {
                    onChainLoaded.accept(sourced.chain());
                }
            } catch (RuntimeException e) {
                warnings.add("expiry " + expiry + ": " + rootMessage(e));
            }
        }
        // Never fit across a generated chain and market chains: a synthetic fallback is built around a nominal
        // price (100), so one of them among real chains at 777 makes the surface, the spot and every quote wrong.
        if (sourcedChains.stream().anyMatch(OptionChainProvider.SourcedChain::marketData)
                && sourcedChains.stream().anyMatch(s -> !s.marketData())) {
            for (OptionChainProvider.SourcedChain dropped : sourcedChains.stream().filter(s -> !s.marketData()).toList()) {
                warnings.add("expiry " + dropped.chain().expiry() + ": " + dropped.source()
                        + " fallback dropped because the other expiries have market data");
            }
            sourcedChains.removeIf(s -> !s.marketData());
            chains.clear();
            sourcedChains.forEach(s -> chains.add(s.chain()));
            sources.clear();
            sourcedChains.forEach(s -> sources.add(s.source()));
            marketData = true;
        }
        return new Loaded(chains, sourcedChains, sources, marketData);
    }

    /** Runs one calibration now, on the calling thread: reloads the chains and refits the surfaces. Safe to call from tests and from the worker. */
    public synchronized void refresh() {
        Instant now = clock.instant();
        lastFitAt = now;
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Set<String> warnings = new LinkedHashSet<>(); // a provider failing on every expiry is reported once
        Loaded loaded = load(today, warnings, true);
        List<OptionChain> chains = loaded.chains();
        List<OptionChainProvider.SourcedChain> sourcedChains = loaded.sourced();
        Set<String> sources = loaded.sources();
        boolean marketData = loaded.marketData();
        if (chains.isEmpty()) {
            status = new Status(State.FAILED, "no option chain for " + symbol + (warnings.isEmpty() ? "" : ": " + warnings.iterator().next()), now);
            return;
        }

        latestChains = List.copyOf(sourcedChains); // quotes are usable even if no surface can be fitted to them
        lastQuoteRefreshAt = now;

        SurfaceFitter.Extraction extraction = SurfaceFitter.extractPoints(chains, riskFreeRate, dividendYield, today);
        warnings.addAll(extraction.warnings());
        // Nine grid rows draw as a sheet; the fitted expiries are kept exactly and reported separately.
        SurfaceFitter.Fit ssvi = SurfaceFitter.densify(tryFit("SSVI", () -> SurfaceFitter.fitSsvi(extraction.points()), warnings), 9);
        SurfaceFitter.Fit sabr = SurfaceFitter.densify(tryFit("SABR", () -> SurfaceFitter.fitSabr(extraction.points()), warnings), 9);
        SurfaceFitter.Fit svi = SurfaceFitter.densify(tryFit("SVI", () -> SurfaceFitter.fitSvi(extraction.points()), warnings), 9);
        if (ssvi == null && sabr == null && svi == null) {
            status = new Status(State.FAILED, "could not fit a surface to " + extraction.points().size() + " quotes: " + lastOrEmpty(warnings), now);
            return;
        }
        Snapshot snapshot = new Snapshot(now, symbol, String.join("+", sources), marketData, chains.get(0).spot(), ssvi, sabr, svi,
                extraction.quotesSkipped(), List.copyOf(warnings));
        latest = snapshot;
        status = new Status(State.READY, String.format(java.util.Locale.ROOT, "%d quotes from %s", extraction.points().size(), String.join("+", sources)), now);
        try {
            snapshotListener.accept(snapshot);
        } catch (RuntimeException e) {
            System.err.println("[SURFACE HISTORY] calibration not recorded: " + rootMessage(e));
        }
    }

    /** Starts the background worker: calibrate now, reload quotes every quote interval, refit every refresh interval, until {@link #close()}. */
    public synchronized void start() {
        if (worker != null) {
            return;
        }
        worker = new Thread(() -> {
            while (!closed) {
                runOnce();
                try {
                    Thread.sleep(nextDelay().toMillis());
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

    // ------------------------------------------------------------------ OptionMarketData

    @Override
    public List<LocalDate> loadedExpiries() {
        return latestChains.stream().map(s -> s.chain().expiry()).sorted().toList();
    }

    @Override
    public Optional<OptionChain> chain(LocalDate expiry) {
        return latestChains.stream().map(OptionChainProvider.SourcedChain::chain).filter(c -> c.expiry().equals(expiry)).findFirst();
    }

    /**
     * The contract's quote as a tradable snapshot: its own bid/ask, the standard 100 multiplier, the chain's
     * provenance (SIMULATED for a generated chain) and the feed's as-of time for freshness.
     */
    @Override
    public Optional<com.sbk.optionspricer.market.MarketSnapshot> optionQuote(String contractSymbol) {
        var parsed = com.sbk.optionspricer.instruments.OccSymbol.parse(contractSymbol);
        if (parsed.isEmpty() || !parsed.get().underlying().equals(symbol)) {
            return Optional.empty();
        }
        var contract = parsed.get();
        for (OptionChainProvider.SourcedChain sourced : latestChains) {
            if (!sourced.chain().expiry().equals(contract.expiry())) {
                continue;
            }
            for (com.sbk.optionspricer.OptionQuote quote : sourced.chain().quotes()) {
                if (quote.type() == contract.type() && Math.abs(quote.strike() - contract.strike()) < 1e-9) {
                    return com.sbk.optionspricer.market.OptionQuoteSnapshots.toSnapshot(quote, symbol,
                            com.sbk.optionspricer.market.OptionQuoteSnapshots.STANDARD_EQUITY_MULTIPLIER,
                            sourced.source(), sourced.marketData(), sourced.asOf(), clock.instant());
                }
            }
        }
        return Optional.empty();
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
