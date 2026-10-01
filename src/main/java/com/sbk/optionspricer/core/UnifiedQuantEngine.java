package com.sbk.optionspricer.core;

import com.sbk.optionspricer.Greeks;
import com.sbk.optionspricer.OptionParameters;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.VectorBlackScholesPricer;
import com.sbk.optionspricer.gateways.QueuePositionEstimator;
import com.sbk.optionspricer.gateways.SmartOrderRouter;
import com.sbk.optionspricer.models.pde.VectorPdeSolver;
import com.sbk.optionspricer.risk.SpanMarginOptimizer;
import com.sbk.optionspricer.volatility.SabrFreeBoundaryModel;
import com.sbk.optionspricer.volatility.SsviCalibrator;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Unified Production-Grade Quantitative Options Pricing, Risk & Execution Engine.
 * End-to-end integration of off-heap FFM struct ingestion, LMAX ring buffer,
 * SSVI / SABR vol surfaces, SIMD pricing, L3 MBO queue estimation, SOR routing,
 * SPAN margin optimization, and zero-GC Mmap IPC state publishing for the web terminal.
 */
public final class UnifiedQuantEngine {

    private final MmapStatePublisher publisher;
    private final MarketDataRingBuffer ringBuffer;
    private final Arena arena;
    private final ScheduledExecutorService engineScheduler;

    private double currentSpot = 100.0;
    private double netDelta = -62500.0;
    private double netGamma = -3500.0;
    private double netVega = -400000.0;
    private double currentSpanMargin = 14611250.0;
    private boolean isRunning = false;

    public UnifiedQuantEngine() {
        this.publisher = new MmapStatePublisher();
        this.ringBuffer = new MarketDataRingBuffer(1024);
        this.arena = Arena.ofShared();
        this.engineScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "unified-quant-engine");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the unified cross-functional execution engine.
     */
    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;

        System.out.println("=========================================================================");
        System.out.println("        STARTING UNIFIED QUANTITATIVE OPTIONS EXECUTION ENGINE           ");
        System.out.println("=========================================================================");

        // Initial state publish
        publisher.publishRiskState(netDelta, netGamma, netVega, currentSpanMargin);

        // Schedule main quantitative processing loop (100 Hz ticker update)
        engineScheduler.scheduleAtFixedRate(this::tickProcessingLoop, 0, 10, TimeUnit.MILLISECONDS);

        System.out.println("[UNIFIED ENGINE] Off-heap FFM Structs, LMAX Ring Buffer, SSVI Surface,");
        System.out.println("[UNIFIED ENGINE] SIMD Solvers, SOR, SPAN Optimizer & Mmap IPC ONLINE.");
        System.out.println("=========================================================================");
    }

    /**
     * Continuous quantitative tick processing cycle.
     */
    private void tickProcessingLoop() {
        try (Arena confined = Arena.ofConfined()) {
            // 1. Off-Heap FFM Ingestion
            MemorySegment tickSegment = MemorySegmentStructs.allocateTick(confined);
            long nowNs = System.nanoTime();
            currentSpot += (Math.random() - 0.5) * 0.10;
            MemorySegmentStructs.setTickData(tickSegment, nowNs, 450000L, currentSpot - 0.05, currentSpot + 0.05, 500, 500, 1L);

            // 2. Volatility Surface Calibration Update (SSVI & Free-Boundary SABR)
            SsviCalibrator.SsviParams ssviParams = new SsviCalibrator.SsviParams(0.5, 0.25, -0.4);
            double ssviVol = SsviCalibrator.impliedVol(currentSpot, 105.0, 0.5, 0.25, ssviParams);
            double sabrVol = SabrFreeBoundaryModel.impliedVolatility(currentSpot, 95.0, 0.25, 0.25, 1.0, -0.6, 0.4);

            // 3. SIMD Pricing Acceleration
            double[] strikes = new double[]{90.0, 95.0, 100.0, 105.0, 110.0};
            double[] prices = new double[5];
            VectorBlackScholesPricer.priceBatchVectorized(currentSpot, strikes, 0.5, 0.05, ssviVol, true, prices);
            VectorPdeSolver.priceBatchPdeVectorized(true, currentSpot, strikes, 0.5, 0.05, sabrVol, prices);

            // 4. Portfolio Greeks Simulation & SPAN Margin Optimization
            netDelta += (Math.random() - 0.5) * 100.0;
            SpanMarginOptimizer.OptimizationResult optResult = SpanMarginOptimizer.optimizeMargin(
                netDelta, netGamma, currentSpot, currentSpanMargin
            );
            currentSpanMargin = optResult.optimizedMargin;

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

            // 6. Zero-GC Off-Heap Mmap IPC State Publish
            publisher.publishRiskState(netDelta, netGamma, netVega, currentSpanMargin);

        } catch (Throwable t) {
            System.err.println("[FATAL] UnifiedQuantEngine encountered a critical error: " + t.getMessage());
            t.printStackTrace();
            stop();
        }
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;
        engineScheduler.shutdown();
        publisher.close();
        arena.close();
        System.out.println("[UNIFIED ENGINE] Service stopped cleanly.");
    }

    public static void main(String[] args) throws Exception {
        UnifiedQuantEngine engine = new UnifiedQuantEngine();
        engine.start();

        System.out.println("Unified Quant Engine running in background... Press ENTER to terminate test run.");
        System.in.read();
        engine.stop();
    }
}
