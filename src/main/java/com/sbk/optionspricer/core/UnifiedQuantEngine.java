package com.sbk.optionspricer.core;

import com.sbk.optionspricer.CompositeOptionChainProvider;
import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionChainProvider;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.SyntheticOptionChainProvider;
import com.sbk.optionspricer.YahooFinanceOptionChain;
import com.sbk.optionspricer.config.ConfigManager;
import com.sbk.optionspricer.config.ConfigValidator;
import com.sbk.optionspricer.data.HistoricalDataManager;
import com.sbk.optionspricer.data.OptionSnapshot;
import com.sbk.optionspricer.risk.PortfolioBacktestOrchestrator;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Risk engine: publishes portfolio Greeks and a stress-test scenario margin derived from the live
 * position book, raises Greek limit alerts, and runs portfolio backtests. Option-chain loading and
 * historical snapshot storage happen in the surface service, at each calibration.
 */
public final class UnifiedQuantEngine {

    private final MmapStatePublisher publisher;
    private final OptionChainProvider optionChainProvider;
    private final HistoricalDataManager historicalDataManager;

    private double currentSpot = 100.0;
    // Risk comes from the exposure source (the real position tracker); an empty book is flat.
    private double netDelta = 0.0;
    private double netGamma = 0.0;
    private double netVega = 0.0;
    private double scenarioMargin = 0.0;
    private volatile java.util.function.Supplier<com.sbk.optionspricer.execution.PositionTracker.PortfolioExposure> exposureSource =
            () -> new com.sbk.optionspricer.execution.PositionTracker.PortfolioExposure(0.0, 0.0, 0.0, 0.0);
    /** Fraction of a limit at which a WARNING is raised; the CRITICAL alert (which halts trading) fires at the limit itself. */
    public static final double WARNING_FRACTION = 0.8;
    private volatile com.sbk.optionspricer.risk.GreekAlertManager greekAlertManager =
            alertsFor(com.sbk.optionspricer.config.ConfigManager.DEFAULT_MAX_DELTA,
                    com.sbk.optionspricer.config.ConfigManager.DEFAULT_MAX_GAMMA,
                    com.sbk.optionspricer.config.ConfigManager.DEFAULT_MAX_VEGA);

    private static com.sbk.optionspricer.risk.GreekAlertManager alertsFor(double maxDelta, double maxGamma, double maxVega) {
        return new com.sbk.optionspricer.risk.GreekAlertManager(WARNING_FRACTION * maxDelta, maxDelta,
                WARNING_FRACTION * maxGamma, maxGamma, WARNING_FRACTION * maxVega, maxVega);
    }

    /**
     * Sets the Greek alert thresholds from the configured limits: WARNING at {@link #WARNING_FRACTION} of each,
     * CRITICAL (halt) at the limit, the same numbers the portfolio admission gate enforces. Call before attaching
     * listeners; it replaces the manager.
     */
    public void configureGreekAlerts(double maxDelta, double maxGamma, double maxVega) {
        if (!(maxDelta > 0.0) || !(maxGamma > 0.0) || !(maxVega > 0.0)) {
            throw new IllegalArgumentException("alert limits must be positive");
        }
        this.greekAlertManager = alertsFor(maxDelta, maxGamma, maxVega);
    }

    /** Supplies the portfolio exposure the engine publishes and monitors (normally {@code PositionTracker::snapshotExposure}). */
    public void setExposureSource(java.util.function.Supplier<com.sbk.optionspricer.execution.PositionTracker.PortfolioExposure> source) {
        if (source == null) {
            throw new IllegalArgumentException("exposure source must not be null");
        }
        this.exposureSource = source;
    }

    private void refreshRisk(double spot) {
        com.sbk.optionspricer.execution.PositionTracker.PortfolioExposure exposure = exposureSource.get();
        netDelta = exposure.netDelta();
        netGamma = exposure.netGamma();
        netVega = exposure.netVega();
        // The stress test shocks the spot, so without a market price the margin is unknown (NaN), not 0.
        scenarioMargin = Double.isFinite(spot) && spot > 0.0
                ? com.sbk.optionspricer.risk.MarginApproximation.calculateInitialMargin(netDelta, netGamma, netVega, spot)
                : Double.NaN;
    }

    public com.sbk.optionspricer.risk.GreekAlertManager getGreekAlertManager() {
        return greekAlertManager;
    }

    public enum EngineState { RUNNING, STOPPED_FATAL }
    private volatile EngineState engineState = EngineState.RUNNING;
    private final java.util.function.IntConsumer exitHandler;

    public UnifiedQuantEngine(MmapStatePublisher publisher) {
        this(publisher, new SyntheticOptionChainProvider(), new HistoricalDataManager(Path.of("data", "historical")), System::exit);
    }

    public UnifiedQuantEngine(MmapStatePublisher publisher, OptionChainProvider optionChainProvider) {
        this(publisher, optionChainProvider, new HistoricalDataManager(Path.of("data", "historical")), System::exit);
    }

    public UnifiedQuantEngine(MmapStatePublisher publisher, java.util.function.IntConsumer exitHandler) {
        this(publisher, new SyntheticOptionChainProvider(), new HistoricalDataManager(Path.of("data", "historical")), exitHandler);
    }

    public UnifiedQuantEngine(MmapStatePublisher publisher, OptionChainProvider optionChainProvider, HistoricalDataManager historicalDataManager, java.util.function.IntConsumer exitHandler) {
        this.publisher = publisher;
        this.optionChainProvider = optionChainProvider;
        this.historicalDataManager = historicalDataManager;
        this.exitHandler = exitHandler;
    }

    public static UnifiedQuantEngine fromConfig(ConfigManager config, MmapStatePublisher publisher) {
        return fromConfig(config, publisher, System::exit);
    }

    /** As {@link #fromConfig(ConfigManager, MmapStatePublisher)} with an injectable fatal-exit handler, for tests. */
    static UnifiedQuantEngine fromConfig(ConfigManager config, MmapStatePublisher publisher, java.util.function.IntConsumer exitHandler) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        List<String> validationErrors = ConfigValidator.validate(config);
        if (!validationErrors.isEmpty()) {
            throw new IllegalArgumentException("Invalid config: " + String.join(", ", validationErrors));
        }

        double spot = config.getDouble("market_data.spot", 100.0);
        double volatility = config.getDouble("volatility.default_volatility", 0.20);
        double riskFreeRate = config.getDouble("market_data.risk_free_rate", 0.05);
        double dividendYield = config.getDouble("market_data.dividend_yield", 0.0);
        List<String> sources = (List<String>) config.get("market_data.sources");
        java.util.List<OptionChainProvider> providers = new java.util.ArrayList<>();
        if (sources != null) {
            for (String source : sources) {
                if ("cboe".equalsIgnoreCase(source)) {
                    providers.add(new com.sbk.optionspricer.CboeOptionChain());
                } else if ("yahoo_finance".equalsIgnoreCase(source)) {
                    providers.add(new YahooFinanceOptionChain());
                }
            }
        }
        // Always append SyntheticOptionChainProvider as a fallback if real APIs fail
        providers.add(new SyntheticOptionChainProvider(spot, volatility, riskFreeRate, dividendYield));

        UnifiedQuantEngine engine = new UnifiedQuantEngine(
                publisher,
                new CompositeOptionChainProvider(providers),
                new HistoricalDataManager(Path.of("data", "historical")),
                exitHandler
        );
        // The alerts that halt trading use the same limits the admission gate enforces, not separate constants.
        engine.configureGreekAlerts(
                config.getDouble("risk.max_delta", ConfigManager.DEFAULT_MAX_DELTA),
                config.getDouble("risk.max_gamma", ConfigManager.DEFAULT_MAX_GAMMA),
                config.getDouble("risk.max_vega", ConfigManager.DEFAULT_MAX_VEGA));
        return engine;
    }

    public EngineState getState() { return engineState; }

    public OptionChain getCurrentOptionChain(String symbol) {
        LocalDate expiry = LocalDate.now().plusDays(30);
        return optionChainProvider.getOptionChain(symbol, expiry);
    }

    public OptionChainProvider getOptionChainProvider() {
        return optionChainProvider;
    }

    /** Stores a few quotes of a freshly loaded chain in the historical store (called by the surface service). */
    public void recordChainSnapshots(OptionChain chain) {
        if (chain == null) {
            return;
        }
        Instant now = Instant.now();
        for (OptionQuote quote : chain.quotes().stream().limit(4).toList()) {
            historicalDataManager.store(new OptionSnapshot(now, quote.symbol(), quote.expiry(), quote.strike(), quote.type(),
                    chain.spot(), quote.bid(), quote.ask(), quote.impliedVolatility(), quote.volume(), quote.openInterest()));
        }
    }

    public void initialize() {
        System.out.println("=========================================================================");
        System.out.println("        STARTING UNIFIED QUANTITATIVE OPTIONS EXECUTION ENGINE           ");
        System.out.println("=========================================================================");
        refreshRisk(currentSpot);
        publisher.publishRiskState(netDelta, netGamma, netVega, scenarioMargin);
        // Option chains are loaded and the surface fitted by VolatilitySurfaceService on its own thread;
        // nothing here waits on the network, so the dashboard comes up immediately.
        System.out.println("[UNIFIED ENGINE] Risk state, margin approximation and mmap IPC online; surface calibration runs in the background.");
        System.out.println("=========================================================================");
    }

    public PortfolioBacktestOrchestrator.PortfolioBacktestResult runPortfolioBacktest(List<OptionSnapshot> snapshots) {
        if (snapshots == null) {
            throw new IllegalArgumentException("snapshots must not be null");
        }
        PortfolioBacktestOrchestrator.PortfolioBacktestResult result = PortfolioBacktestOrchestrator.run(snapshots);
        System.out.printf(java.util.Locale.ROOT,
                "[BACKTEST] snapshots=%d signals=%d accepted=%d rejected=%d netQty=%d pnl=%.2f slippage=%.2f drawdown=%.2f%n",
                result.snapshotsProcessed(), result.totalSignals(), result.acceptedOrders(), result.rejectedOrders(),
                result.netQuantity(), result.totalPnL(), result.averageSlippage(), result.maxDrawdown());
        return result;
    }

    /**
     * One engine cycle: read the portfolio exposure, derive scenario margin, check Greek limits and
     * publish the risk state over the mmap IPC channel. Cheap and allocation-light by design;
     * pricing and calibration are not done here.
     */
    public void processTick(double spot) {
        if (engineState == EngineState.STOPPED_FATAL) return;
        try {
            currentSpot = spot;
            refreshRisk(spot);

            com.sbk.optionspricer.risk.GreekRiskMonitor.PortfolioRisk currentRisk = new com.sbk.optionspricer.risk.GreekRiskMonitor.PortfolioRisk(netDelta, netGamma, netVega, 0.0, 0.0, 0.0, 0.0, 0.0);
            greekAlertManager.checkLimits(currentRisk);

            publisher.publishRiskState(netDelta, netGamma, netVega, scenarioMargin);
        } catch (Throwable t) {
            // Fail-stop: an unexpected error here means the published risk can no longer be trusted.
            if (this.engineState == EngineState.STOPPED_FATAL) return;
            this.engineState = EngineState.STOPPED_FATAL;
            publisher.publishUnavailable();
            System.err.println("[FATAL] UnifiedQuantEngine encountered a critical error: " + t.getMessage());
            t.printStackTrace();
            exitHandler.accept(1);
        }
    }

    public synchronized void stop() {
        publisher.close();
        if (engineState == EngineState.STOPPED_FATAL) {
            System.out.println("[UNIFIED ENGINE] Stopped after fatal error.");
        } else {
            System.out.println("[UNIFIED ENGINE] Service stopped cleanly.");
        }
    }
}
