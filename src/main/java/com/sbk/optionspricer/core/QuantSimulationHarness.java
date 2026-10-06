package com.sbk.optionspricer.core;

import com.sbk.optionspricer.execution.OrderManager;
import com.sbk.optionspricer.execution.PortfolioRiskAdmission;
import com.sbk.optionspricer.execution.PositionTracker;
import com.sbk.optionspricer.execution.StrategyExecutionLoop;
import com.sbk.optionspricer.web.LiveSpotProvider;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class QuantSimulationHarness {
    private final UnifiedQuantEngine engine;
    private final ScheduledExecutorService engineScheduler;
    private final LiveSpotProvider spotProvider;
    private final PositionTracker positionTracker;
    private final OrderManager orderManager;
    private final PortfolioRiskAdmission riskAdmission;
    private final StrategyExecutionLoop strategyLoop;
    private final Deque<Double> recentPrices = new ArrayDeque<>();
    private boolean isRunning = false;
    
    private double currentSpot = 100.0;

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

        var feedStatus = spotProvider.getFeedStatus("SPY");
        System.out.println("[MARKET DATA STATUS] FINNHUB=" + feedStatus.getOrDefault("FINNHUB", "UNAVAILABLE")
                + " | ALPHA_VANTAGE=" + feedStatus.getOrDefault("ALPHA_VANTAGE", "UNAVAILABLE")
                + " | POLYGON=" + feedStatus.getOrDefault("POLYGON", "UNAVAILABLE")
                + " | MARKETSTACK=" + feedStatus.getOrDefault("MARKETSTACK", "UNAVAILABLE"));

        engine.initialize();

        engineScheduler.scheduleAtFixedRate(this::tick, 0, 10, TimeUnit.MILLISECONDS);
    }
    
    public synchronized StrategyExecutionLoop.ExecutionSummary runStrategyStep(double price) {
        if (strategyLoop == null) {
            return new StrategyExecutionLoop.ExecutionSummary(0, 0, 0, 0);
        }
        recentPrices.addLast(price);
        if (recentPrices.size() > 10) {
            recentPrices.removeFirst();
        }
        List<Double> window = new ArrayList<>(recentPrices);
        List<com.sbk.optionspricer.market.MarketSnapshot> contexts = new ArrayList<>(window.size());
        for (double px : window) {
            double spread = Math.max(0.01, px * 0.0005);
            contexts.add(new com.sbk.optionspricer.market.MarketSnapshot("SPY", px - spread, px + spread, px, 2000L, java.time.Instant.now(), java.time.Instant.now(), 0L, "SIMULATED", com.sbk.optionspricer.market.MarketDataStatus.SIMULATED));
        }
        return strategyLoop.run(window, contexts);
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
                spot = 100.0;
            }
            
            currentSpot = spot;

            engine.processTick(spot);
            if (strategyLoop != null) {
                runStrategyStep(spot);
            }
        } catch (Exception e) {
            if (engine.getState() == UnifiedQuantEngine.EngineState.STOPPED_FATAL) {
                throw new RuntimeException("Engine stopped fatally, halting scheduler.", e);
            }
            e.printStackTrace();
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
