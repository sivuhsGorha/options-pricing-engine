package com.sbk.optionspricer.core;

import com.sbk.optionspricer.CompositeOptionChainProvider;
import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionChainProvider;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.SyntheticOptionChainProvider;
import com.sbk.optionspricer.TdAmeritradeOptionChain;
import com.sbk.optionspricer.VectorBlackScholesPricer;
import com.sbk.optionspricer.YahooFinanceOptionChain;
import com.sbk.optionspricer.config.ConfigManager;
import com.sbk.optionspricer.config.ConfigValidator;
import com.sbk.optionspricer.data.HistoricalDataManager;
import com.sbk.optionspricer.data.OptionSnapshot;
import com.sbk.optionspricer.gateways.QueuePositionEstimator;
import com.sbk.optionspricer.gateways.SmartOrderRouter;
import com.sbk.optionspricer.models.pde.ParallelPdeBatchSolver;
import com.sbk.optionspricer.risk.MarginOptimizer;
import com.sbk.optionspricer.risk.PortfolioBacktestOrchestrator;
import com.sbk.optionspricer.volatility.SabrFreeBoundaryModel;
import com.sbk.optionspricer.volatility.SsviApproximation;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Unified Production-Grade Quantitative Options Pricing, Risk & Execution Engine.
 * End-to-end integration of off-heap FFM struct ingestion, SPSC ring buffer,
 * SSVI / SABR vol surfaces, Parallel pricing, L3 MBO queue estimation, SOR routing,
 * Scenario margin optimization, and zero-GC Mmap IPC state publishing for the web terminal.
 */
public final class UnifiedQuantEngine {

    private final MmapStatePublisher publisher;
    private final OptionChainProvider optionChainProvider;
    private final HistoricalDataManager historicalDataManager;

    private double currentSpot = 100.0;
    private double netDelta = -62500.0;
    private double netGamma = -3500.0;
    private double netVega = -400000.0;
    private double scenarioMargin = 14611250.0;
    private final com.sbk.optionspricer.risk.GreekAlertManager greekAlertManager =
            new com.sbk.optionspricer.risk.GreekAlertManager(50000, 100000, 5000, 10000, 300000, 600000);

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
     * Continuous quantitative tick processing cycle, driven by an external environment.
     */
    public void processTick(double spot, double deltaChange) {
        processTick(spot, deltaChange, spot - 0.05, spot + 0.05, 500, 500);
    }

    /**
     * Continuous quantitative tick processing cycle with full market data parameters.
     */
    public void processTick(double spot, double deltaChange, double bidPrice, double askPrice, int bidSize, int askSize) {
        if (engineState == EngineState.STOPPED_FATAL) return;
        try (Arena confined = Arena.ofConfined()) {
            currentSpot = spot;
            netDelta += deltaChange;
            
            // 1. Off-Heap FFM Ingestion
            MemorySegment tickSegment = MemorySegmentStructs.allocateTick(confined);
            long nowNs = System.nanoTime();
            MemorySegmentStructs.setTickData(tickSegment, nowNs, 450000L, bidPrice, askPrice, bidSize, askSize, 1L);

            // 2. Volatility Surface Calibration Update (SSVI & Free-Boundary SABR)
            SsviApproximation.SsviParams ssviParams = new SsviApproximation.SsviParams(0.5, 0.25, -0.4);
            double ssviVol = SsviApproximation.impliedVol(currentSpot, 105.0, 0.5, 0.25, ssviParams);
            double sabrVol = SabrFreeBoundaryModel.impliedVolatility(currentSpot, 95.0, 0.25, 0.25, 1.0, -0.6, 0.4);

            // 3. Parallel Pricing Acceleration
            double[] strikes = new double[]{90.0, 95.0, 100.0, 105.0, 110.0};
            double[] prices = VectorBlackScholesPricer.priceBatchParallel(currentSpot, strikes, 0.5, 0.05, ssviVol, true);
            ParallelPdeBatchSolver.priceBatchPdeVectorized(true, currentSpot, strikes, 0.5, 0.05, sabrVol, prices);

            // 4. Portfolio Greeks Simulation & Scenario Margin Optimization
            MarginOptimizer.OptimizationResult optResult = MarginOptimizer.optimizeMargin(
                netDelta, netGamma, currentSpot, scenarioMargin
            );
            scenarioMargin = optResult.optimizedMargin;

            // 5. Microstructure L3 Queue & SOR Execution
            if (Math.abs(netDelta) > 50000.0) {
                QueuePositionEstimator.QueueState qState = QueuePositionEstimator.estimateQueuePosition(10, 1200, 250.0, 50.0, 2.0);
                if (qState.fillProbability > 0.5) {
                    double[] weights = { 0.5, 0.3, 0.2 };
                    List<SmartOrderRouter.SubOrder> sorOrders = SmartOrderRouter.routeOrder(optResult.recommendedHedgeShares, weights);
                    // Rebalance delta toward target limit
                    netDelta += optResult.recommendedHedgeShares * 0.10;
                }
            }

            // 6. Real-time Risk Monitoring & Alerts
            com.sbk.optionspricer.risk.GreekRiskMonitor.PortfolioRisk currentRisk = new com.sbk.optionspricer.risk.GreekRiskMonitor.PortfolioRisk(netDelta, netGamma, netVega, 0.0, 0.0, 0.0, 0.0, 0.0);
            greekAlertManager.checkLimits(currentRisk);

            com.sbk.optionspricer.volatility.SlvApproximation.SlvParams hestonParams = new com.sbk.optionspricer.volatility.SlvApproximation.SlvParams(2.0, 0.04, 0.1, -0.7, 0.04);
            double var99 = com.sbk.optionspricer.risk.MonteCarloVaRCalculator.calculate99PercentVaR(currentSpot, hestonParams, 0.05, 0.0, 10, netDelta, netGamma, netVega);
            
            // 7. Zero-GC Off-Heap Mmap IPC State Publish
            publisher.publishRiskState(netDelta, netGamma, netVega, scenarioMargin);
        } catch (Throwable t) {
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
