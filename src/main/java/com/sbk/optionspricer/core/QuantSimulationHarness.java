package com.sbk.optionspricer.core;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class QuantSimulationHarness {
    private final UnifiedQuantEngine engine;
    private final ScheduledExecutorService engineScheduler;
    private boolean isRunning = false;
    
    private double simulatedSpot = 100.0;

    public QuantSimulationHarness(UnifiedQuantEngine engine) {
        this.engine = engine;
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
        System.out.println("        STARTING QUANT SIMULATION HARNESS (100 Hz)                       ");
        System.out.println("=========================================================================");
        
        engine.initialize();
        
        engineScheduler.scheduleAtFixedRate(this::tick, 0, 10, TimeUnit.MILLISECONDS);
    }
    
    private void tick() {
        try {
            simulatedSpot += (Math.random() - 0.5) * 0.10;
            double simulatedDeltaChange = (Math.random() - 0.5) * 100.0;
            
            engine.processTick(simulatedSpot, simulatedDeltaChange);
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
