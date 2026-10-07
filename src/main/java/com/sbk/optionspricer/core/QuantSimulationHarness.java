package com.sbk.optionspricer.core;

import com.sbk.optionspricer.execution.OrderManager;
import com.sbk.optionspricer.execution.PortfolioRiskAdmission;
import com.sbk.optionspricer.execution.PositionTracker;
import com.sbk.optionspricer.execution.StrategyExecutionLoop;
import com.sbk.optionspricer.web.LiveSpotProvider;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class QuantSimulationHarness implements com.sbk.optionspricer.execution.StrategySwitch {
    private final UnifiedQuantEngine engine;
    private final ScheduledExecutorService engineScheduler;
    private final LiveSpotProvider spotProvider;
    private final PositionTracker positionTracker;
    private final OrderManager orderManager;
    private final PortfolioRiskAdmission riskAdmission;
    private final StrategyExecutionLoop strategyLoop;
    private boolean isRunning = false;
    /** Operator switch: when off, prices are still processed for risk but the strategy generates no orders. */
    private volatile boolean strategyEnabled = true;
    
    // NaN until a real price arrives: nothing downstream may be fed an invented spot.
    private double currentSpot = Double.NaN;

    public QuantSimulationHarness(UnifiedQuantEngine engine) {
        this(engine, new LiveSpotProvider());
    }

    public QuantSimulationHarness(UnifiedQuantEngine engine, LiveSpotProvider spotProvider) {
        this(engine, spotProvider, null, null, null, null);
    }

    public QuantSimulationHarness(UnifiedQuantEngine engine, LiveSpotProvider spotProvider,
                                 PositionTracker positionTracker, OrderManager orderManager,
                                 PortfolioRiskAdmission riskAdmission, StrategyExecutionLoop strategyLoop) {
        this.engine = engine;
        this.spotProvider = spotProvider;
        this.positionTracker = positionTracker;
        this.orderManager = orderManager;
        this.riskAdmission = riskAdmission;
        this.strategyLoop = strategyLoop;
        this.engineScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "quant-simulation-harness");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;

        System.out.println("=========================================================================");
        if (spotProvider.hasMarketDataKeys()) {
            System.out.println("        STARTING QUANT HARNESS WITH LIVE MARKET DATA (100 Hz)          ");
        } else {
            System.out.println("        WAITING FOR LIVE FEED KEYS — MARKET DATA DISABLED             ");
        }
        System.out.println("=========================================================================");

        // A diagnostic only: probing up to four providers over the network can take many seconds, and the
        // dashboard does not start until start() returns, so it runs on its own thread.
        Thread feedProbe = new Thread(() -> {
            try {
                var feedStatus = spotProvider.getFeedStatus("SPY");
                System.out.println("[MARKET DATA STATUS] FINNHUB=" + feedStatus.getOrDefault("FINNHUB", "UNAVAILABLE")
                        + " | ALPHA_VANTAGE=" + feedStatus.getOrDefault("ALPHA_VANTAGE", "UNAVAILABLE")
                        + " | POLYGON=" + feedStatus.getOrDefault("POLYGON", "UNAVAILABLE")
                        + " | MARKETSTACK=" + feedStatus.getOrDefault("MARKETSTACK", "UNAVAILABLE"));
            } catch (RuntimeException e) {
                System.err.println("[MARKET DATA STATUS] probe failed: " + e);
            }
        }, "feed-status-probe");
        feedProbe.setDaemon(true);
        feedProbe.start();

        engine.initialize();

        engineScheduler.scheduleAtFixedRate(this::tick, 0, 10, TimeUnit.MILLISECONDS);
    }
    
    @Override
    public boolean isStrategyEnabled() {
        return strategyEnabled;
    }

    @Override
    public void setStrategyEnabled(boolean enabled) {
        this.strategyEnabled = enabled;
    }

    public synchronized StrategyExecutionLoop.ExecutionSummary runStrategyStep(double price) {
        if (strategyLoop == null || !strategyEnabled) {
            return new StrategyExecutionLoop.ExecutionSummary(0, 0, 0, 0);
        }
        return strategyLoop.onPrice(price, null);
    }

    private void tick() {
        try {
            double spot = currentSpot;
            if (spotProvider.hasMarketDataKeys()) {
                LiveSpotProvider.Quote quote = spotProvider.getQuote("SPY");
                if (quote != null && !Double.isNaN(quote.last())
                        && com.sbk.optionspricer.market.MarketDataStatus.UNAVAILABLE != quote.status()) {
                    spot = quote.last();
                }
            } else {
                spot = Double.NaN; // no market-data keys: there is no spot to report
            }
            currentSpot = spot;

            engine.processTick(spot);
            if (engine.getState() == UnifiedQuantEngine.EngineState.STOPPED_FATAL) {
                // processTick handles its own fatal path; stop the scheduler instead of ticking no-ops.
                throw new IllegalStateException("Engine stopped fatally, halting scheduler.");
            }
            if (strategyLoop != null && strategyEnabled) {
                runStrategyStep(spot);
            }
        } catch (RuntimeException e) {
            if (engine.getState() == UnifiedQuantEngine.EngineState.STOPPED_FATAL) {
                throw e;
            }
            logTickFailure(e);
        }
    }

    private static final long FAILURE_LOG_INTERVAL_MS = 5_000L;
    private long lastFailureLogMs = 0L;
    private long suppressedFailures = 0L;

    /** A failure repeating every 10 ms tick is logged once per interval, with a count of suppressed repeats. */
    private void logTickFailure(RuntimeException e) {
        long now = System.currentTimeMillis();
        if (lastFailureLogMs == 0L || now - lastFailureLogMs >= FAILURE_LOG_INTERVAL_MS) {
            System.err.println("[SIMULATION HARNESS] tick failed"
                    + (suppressedFailures > 0 ? " (" + suppressedFailures + " similar failures suppressed)" : "")
                    + ": " + e);
            lastFailureLogMs = now;
            suppressedFailures = 0L;
        } else {
            suppressedFailures++;
        }
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;
        engineScheduler.shutdown();
        try {
            if (!engineScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                engineScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            engineScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        engine.stop();
        
        if (engine.getState() == UnifiedQuantEngine.EngineState.STOPPED_FATAL) {
            System.out.println("[SIMULATION HARNESS] Stopped after fatal error.");
        } else {
            System.out.println("[SIMULATION HARNESS] Stopped cleanly.");
        }
    }
}
