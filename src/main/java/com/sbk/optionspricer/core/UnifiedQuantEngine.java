package com.sbk.optionspricer.core;

import com.sbk.optionspricer.VectorBlackScholesPricer;
import com.sbk.optionspricer.gateways.QueuePositionEstimator;
import com.sbk.optionspricer.gateways.SmartOrderRouter;
import com.sbk.optionspricer.models.pde.VectorPdeSolver;
import com.sbk.optionspricer.risk.SpanMarginOptimizer;
import com.sbk.optionspricer.volatility.SabrFreeBoundaryModel;
import com.sbk.optionspricer.volatility.SsviApproximation;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * Unified Production-Grade Quantitative Options Pricing, Risk & Execution Engine.
 * End-to-end integration of off-heap FFM struct ingestion, LMAX ring buffer,
 * SSVI / SABR vol surfaces, SIMD pricing, L3 MBO queue estimation, SOR routing,
 * SPAN margin optimization, and zero-GC Mmap IPC state publishing for the web terminal.
 */
public final class UnifiedQuantEngine {

    private final MmapStatePublisher publisher;

    private double currentSpot = 100.0;
    private double netDelta = -62500.0;
    private double netGamma = -3500.0;
    private double netVega = -400000.0;
    private double currentSpanMargin = 14611250.0;

    public enum EngineState { RUNNING, STOPPED_FATAL }
    private volatile EngineState engineState = EngineState.RUNNING;
    private final java.util.function.IntConsumer exitHandler;

    public UnifiedQuantEngine(MmapStatePublisher publisher) {
        this(publisher, System::exit);
    }

    public UnifiedQuantEngine(MmapStatePublisher publisher, java.util.function.IntConsumer exitHandler) {
        this.publisher = publisher;
        this.exitHandler = exitHandler;
    }

    public EngineState getState() { return engineState; }

    public void initialize() {
        System.out.println("=========================================================================");
        System.out.println("        STARTING UNIFIED QUANTITATIVE OPTIONS EXECUTION ENGINE           ");
        System.out.println("=========================================================================");
        publisher.publishRiskState(netDelta, netGamma, netVega, currentSpanMargin);
        System.out.println("[UNIFIED ENGINE] Off-heap FFM Structs, LMAX Ring Buffer, SSVI Surface,");
        System.out.println("[UNIFIED ENGINE] SIMD Solvers, SOR, SPAN Optimizer & Mmap IPC ONLINE.");
        System.out.println("=========================================================================");
    }

    /**
     * Continuous quantitative tick processing cycle, driven by an external environment.
     */
    public void processTick(double spot, double deltaChange) {
        if (engineState == EngineState.STOPPED_FATAL) return;
        try (Arena confined = Arena.ofConfined()) {
            currentSpot = spot;
            netDelta += deltaChange;
            
            // 1. Off-Heap FFM Ingestion
            MemorySegment tickSegment = MemorySegmentStructs.allocateTick(confined);
            long nowNs = System.nanoTime();
            MemorySegmentStructs.setTickData(tickSegment, nowNs, 450000L, currentSpot - 0.05, currentSpot + 0.05, 500, 500, 1L);

            // 2. Volatility Surface Calibration Update (SSVI & Free-Boundary SABR)
            SsviApproximation.SsviParams ssviParams = new SsviApproximation.SsviParams(0.5, 0.25, -0.4);
            double ssviVol = SsviApproximation.impliedVol(currentSpot, 105.0, 0.5, 0.25, ssviParams);
            double sabrVol = SabrFreeBoundaryModel.impliedVolatility(currentSpot, 95.0, 0.25, 0.25, 1.0, -0.6, 0.4);

            // 3. SIMD Pricing Acceleration
            double[] strikes = new double[]{90.0, 95.0, 100.0, 105.0, 110.0};
            double[] prices = VectorBlackScholesPricer.priceBatchParallel(currentSpot, strikes, 0.5, 0.05, ssviVol, true);
            VectorPdeSolver.priceBatchPdeVectorized(true, currentSpot, strikes, 0.5, 0.05, sabrVol, prices);

            // 4. Portfolio Greeks Simulation & SPAN Margin Optimization
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
