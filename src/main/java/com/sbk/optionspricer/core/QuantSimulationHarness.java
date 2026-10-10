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
    /** {@code momentum} runs the share strategy on every tick; {@code vol_spread} steps the options strategy on its interval. */
    private volatile String strategyMode = "momentum";
    private Runnable optionStrategyStep;
    private long optionStepIntervalMs;
    private long lastOptionStepMs;

    /** Installs the options strategy, stepped at most every {@code interval} while the switch is on and the mode is {@code vol_spread}. */
    public synchronized void setOptionStrategy(Runnable step, java.time.Duration interval) {
        if (step == null || interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("step and a positive interval are required");
        }
        this.optionStrategyStep = step;
        this.optionStepIntervalMs = interval.toMillis();
        this.lastOptionStepMs = Long.MIN_VALUE / 2; // the first step is due immediately
    }

    public void setStrategyMode(String mode) {
        if (mode == null || !(mode.equals("momentum") || mode.equals("vol_spread"))) {
            throw new IllegalArgumentException("strategy mode must be momentum or vol_spread");
        }
        this.strategyMode = mode;
    }

    public String getStrategyMode() {
        return strategyMode;
    }

    /** True when the options strategy is installed, enabled, selected and its interval has elapsed. */
    private synchronized boolean optionStepDue(long nowMs) {
        return optionStrategyStep != null && strategyEnabled && "vol_spread".equals(strategyMode)
                && nowMs - lastOptionStepMs >= optionStepIntervalMs;
    }

    /** Runs the options strategy now if it is installed, enabled, selected and due. Returns true when it ran. */
    public boolean stepOptionStrategyIfDue(long nowMs) {
        Runnable step;
        synchronized (this) {
            step = optionStrategyStep;
            if (!optionStepDue(nowMs)) {
                return false;
            }
            lastOptionStepMs = nowMs;
        }
        step.run();
        return true;
    }

    /**
     * Strategy steps send orders, and an order waits for the venue (up to 15 s with the Alpaca transport). They
     * run on their own thread so the 10 ms engine tick, which publishes risk and checks the Greek limits, never
     * waits on one, and at most one step is in flight: a tick that finds the previous step still running skips.
     */
    private final java.util.concurrent.ExecutorService strategyExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "strategy-orders");
        t.setDaemon(true);
        return t;
    });
    private final java.util.concurrent.atomic.AtomicBoolean strategyBusy = new java.util.concurrent.atomic.AtomicBoolean();

    private void dispatchStrategy(double spot) {
        boolean momentum = strategyLoop != null && strategyEnabled && "momentum".equals(strategyMode);
        if (!momentum && !optionStepDue(System.currentTimeMillis())) {
            return;
        }
        if (!strategyBusy.compareAndSet(false, true)) {
            return; // the previous step is still talking to the venue
        }
        try {
            strategyExecutor.execute(() -> {
                try {
                    if (momentum) {
                        runStrategyStep(spot);
                    }
                    stepOptionStrategyIfDue(System.currentTimeMillis());
                } catch (RuntimeException e) {
                    logTickFailure(e);
                } finally {
                    strategyBusy.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            strategyBusy.set(false);
        }
    }
    
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

    /** One momentum step. Not synchronized: it waits on the venue, and holding this monitor would block stop() and the setters. */
    public StrategyExecutionLoop.ExecutionSummary runStrategyStep(double price) {
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
            dispatchStrategy(spot);
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
    private synchronized void logTickFailure(RuntimeException e) {
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
        strategyExecutor.shutdown();
        try {
            if (!engineScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                engineScheduler.shutdownNow();
            }
            // An order may be waiting on the venue; give it a moment, then interrupt (the Alpaca transport cancels on interrupt).
            if (!strategyExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                strategyExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            engineScheduler.shutdownNow();
            strategyExecutor.shutdownNow();
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
