package com.sbk.optionspricer.core;

import com.sbk.optionspricer.CompositeOptionChainProvider;
import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionChainProvider;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.SyntheticOptionChainProvider;
import com.sbk.optionspricer.TdAmeritradeOptionChain;
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
 * historical snapshot storage happen at initialization.
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
    private final com.sbk.optionspricer.risk.GreekAlertManager greekAlertManager =
            new com.sbk.optionspricer.risk.GreekAlertManager(50000, 100000, 5000, 10000, 300000, 600000);

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
        scenarioMargin = com.sbk.optionspricer.risk.MarginApproximation.calculateInitialMargin(netDelta, netGamma, netVega, spot);
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
                if ("yahoo_finance".equalsIgnoreCase(source)) {
                    providers.add(new YahooFinanceOptionChain());
                } else if ("td_ameritrade".equalsIgnoreCase(source)) {
                    providers.add(new TdAmeritradeOptionChain());
                }
            }
        }
        // Always append SyntheticOptionChainProvider as a fallback if real APIs fail
        providers.add(new SyntheticOptionChainProvider(spot, volatility, riskFreeRate, dividendYield));

        return new UnifiedQuantEngine(
                publisher,
                new CompositeOptionChainProvider(providers),
                new HistoricalDataManager(Path.of("data", "historical")),
                System::exit
        );
    }

    public EngineState getState() { return engineState; }

    public OptionChain getCurrentOptionChain(String symbol) {
        LocalDate expiry = LocalDate.now().plusDays(30);
        return optionChainProvider.getOptionChain(symbol, expiry);
    }

    public void initialize() {
        System.out.println("=========================================================================");
        System.out.println("        STARTING UNIFIED QUANTITATIVE OPTIONS EXECUTION ENGINE           ");
        System.out.println("=========================================================================");
        refreshRisk(currentSpot);
        publisher.publishRiskState(netDelta, netGamma, netVega, scenarioMargin);
        OptionChain liveChain = getCurrentOptionChain("SPY");
        System.out.println("[LIVE MARKET DATA] SPY spot=" + liveChain.spot() + " | strikes=" + liveChain.quotes().size());
        for (OptionQuote quote : liveChain.quotes().stream().limit(4).toList()) {
            historicalDataManager.store(new OptionSnapshot(
                    Instant.now(),
                    quote.symbol(),
                    quote.expiry(),
                    quote.strike(),
                    quote.type(),
                    liveChain.spot(),
                    quote.bid(),
                    quote.ask(),
                    quote.impliedVolatility(),
                    quote.volume(),
                    quote.openInterest()
            ));
        }
        System.out.println("[UNIFIED ENGINE] Off-heap FFM Structs, SPSC ring buffer, SSVI Surface,");
        System.out.println("[UNIFIED ENGINE] Parallel Solvers, SOR, Margin Optimizer & Mmap IPC ONLINE.");
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
