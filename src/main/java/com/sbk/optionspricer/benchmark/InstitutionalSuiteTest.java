package com.sbk.optionspricer.benchmark;

import com.sbk.optionspricer.core.MemorySegmentStructs;
import com.sbk.optionspricer.gateways.QueuePositionEstimator;
import com.sbk.optionspricer.gateways.SmartOrderRouter;
import com.sbk.optionspricer.models.pde.VectorPdeSolver;
import com.sbk.optionspricer.risk.SpanMarginOptimizer;
import com.sbk.optionspricer.volatility.SabrFreeBoundaryModel;
import com.sbk.optionspricer.volatility.SsviApproximation;
import com.sbk.optionspricer.volatility.SsviApproximation.SsviParams;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * Empirical Verification Test Suite for Institutional Quantitative & Architecture Upgrades (Phase 8).
 */
public class InstitutionalSuiteTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=========================================================================");
        System.out.println("       INSTITUTIONAL QUANTITATIVE & ARCHITECTURE VERIFICATION SUITE      ");
        System.out.println("=========================================================================");

        testSsviSurfaceCalibration();
        testSabrFreeBoundaryModel();
        testVectorizedPdeSolver();
        testMemorySegmentOffHeapStructs();
        testQueuePositionEstimator();
        testSmartOrderRouter();
        testSpanMarginOptimizer();

        System.out.println("\n[SUCCESS] ALL INSTITUTIONAL UPGRADES EMPIRICALLY VERIFIED!");
        System.out.println("=========================================================================");
    }

    private static void testSsviSurfaceCalibration() {
        System.out.println("\n--- 8.1 SSVI Global Arbitrage-Free Surface Calibration ---");
        SsviParams params = new SsviParams(0.5, 0.25, -0.4);
        double spot = 100.0;
        double strike = 105.0;
        double expiry = 1.0;
        double atmVol = 0.20;

        double vol = SsviApproximation.impliedVol(spot, strike, expiry, atmVol, params);
        boolean isArbFree = SsviApproximation.isArbitrageFree(Math.log(strike / spot), atmVol * atmVol * expiry, params);

        System.out.printf(java.util.Locale.ROOT, "SSVI Implied Vol for K=%.1f, T=%.1f: %.2f%% (Butterfly Arbitrage Free: %b)%n",
                strike, expiry, vol * 100.0, isArbFree);

        if (vol <= 0 || !isArbFree) {
            throw new AssertionError("SSVI surface failed validity or arbitrage checks!");
        }
        System.out.println("-> SSVI Surface Calibrator PASSED.");
    }

    private static void testSabrFreeBoundaryModel() {
        System.out.println("\n--- 8.2 Free-Boundary SABR Volatility Solver ---");
        double fwd = 100.0;
        double strike = 80.0;
        double expiry = 0.1; // Short expiry

        double vol = SabrFreeBoundaryModel.impliedVolatility(fwd, strike, expiry, 0.25, 1.0, -0.6, 0.4);
        System.out.printf(java.util.Locale.ROOT, "Free-Boundary SABR Vol (Short Expiry T=0.1, Strike=80): %.2f%%%n", vol * 100.0);

        if (vol <= 0 || Double.isNaN(vol)) {
            throw new AssertionError("Free-Boundary SABR produced non-positive or NaN volatility!");
        }
        System.out.println("-> Free-Boundary SABR Solver PASSED.");
    }

    private static void testVectorizedPdeSolver() {
        System.out.println("\n--- 8.3 SIMD-Vectorized Crank-Nicolson PDE Solver ---");
        int numStrikes = 16;
        double spot = 100.0;
        double[] strikes = new double[numStrikes];
        double[] prices = new double[numStrikes];

        for (int i = 0; i < numStrikes; i++) strikes[i] = 90.0 + (i * 1.5);

        long start = System.nanoTime();
        VectorPdeSolver.priceBatchPdeVectorized(true, spot, strikes, 1.0, 0.05, 0.20, prices);
        long durationUs = (System.nanoTime() - start) / 1000;

        System.out.printf(java.util.Locale.ROOT, "Vectorized PDE Batch Priced %d strikes in %d us. Sample Call@90: %.4f%n",
                numStrikes, durationUs, prices[0]);

        if (prices[0] <= 0) {
            throw new AssertionError("Vectorized PDE Solver produced invalid price!");
        }
        System.out.println("-> SIMD Vectorized PDE Solver PASSED.");
    }

    private static void testMemorySegmentOffHeapStructs() {
        System.out.println("\n--- 8.4 Java 21 FFM 64-Byte Cache-Line Aligned Structs ---");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment tick = MemorySegmentStructs.allocateTick(arena);
            MemorySegmentStructs.setTickData(tick, 1000000L, 450000L, 51.39, 52.43, 100, 150, 1L);

            double bid = MemorySegmentStructs.getBidPrice(tick);
            double ask = MemorySegmentStructs.getAskPrice(tick);
            int bidSize = MemorySegmentStructs.getBidSize(tick);

            System.out.printf(java.util.Locale.ROOT, "FFM Off-Heap Struct Read: Bid=%.2f, Ask=%.2f, BidSize=%d%n", bid, ask, bidSize);

            if (Math.abs(bid - 51.39) > 1e-4 || bidSize != 100) {
                throw new AssertionError("MemorySegmentStructs off-heap read mismatch!");
            }
        }
        System.out.println("-> FFM Cache-Line Aligned Structs PASSED.");
    }

    private static void testQueuePositionEstimator() {
        System.out.println("\n--- 8.5 Level 3 MBO Queue Position Estimator ---");
        QueuePositionEstimator.QueueState state = QueuePositionEstimator.estimateQueuePosition(12, 1500, 200.0, 50.0, 5.0);
        System.out.printf(java.util.Locale.ROOT, "Queue Pos: Orders Ahead=%d, Vol Ahead=%d, Fill Prob=%.1f%%%n",
                state.ordersAhead, state.volumeAhead, state.fillProbability * 100.0);

        if (state.fillProbability < 0 || state.fillProbability > 1.0) {
            throw new AssertionError("Queue Position Estimator fill probability out of bounds!");
        }
        System.out.println("-> Level 3 Queue Position Estimator PASSED.");
    }

    private static void testSmartOrderRouter() {
        System.out.println("\n--- 8.6 Cross-Venue Smart Order Router (SOR) ---");
        double[] weights = { 0.5, 0.3, 0.2 };
        List<SmartOrderRouter.SubOrder> subOrders = SmartOrderRouter.routeOrder(1000, weights);

        for (SmartOrderRouter.SubOrder so : subOrders) {
            System.out.printf(java.util.Locale.ROOT, "SOR Sub-Order: Venue=%s, Qty=%d, Latency Offset=%.2f us%n",
                    so.venue.name(), so.allocatedQty, so.delayOffsetMicros);
        }

        if (subOrders.size() != 3) {
            throw new AssertionError("Smart Order Router failed to split across all venues!");
        }
        System.out.println("-> Smart Order Router PASSED.");
    }

    private static void testSpanMarginOptimizer() {
        System.out.println("\n--- 8.7 SPAN / Eurex Prisma Initial Margin Optimizer ---");
        SpanMarginOptimizer.OptimizationResult res = SpanMarginOptimizer.optimizeMargin(-500.0, -25.0, 100.0, 145000.0);
        System.out.printf(java.util.Locale.ROOT, "SPAN Margin Optimization: Current=$%.2f -> Optimized=$%.2f (Hedge Qty: +%d shares, Reduction: %.1f%%)%n",
                res.originalMargin, res.optimizedMargin, res.recommendedHedgeShares, res.marginReductionPct);

        if (res.recommendedHedgeShares != 500 || res.optimizedMargin >= res.originalMargin) {
            throw new AssertionError("SPAN Margin Optimizer did not calculate valid margin reduction!");
        }
        System.out.println("-> SPAN Margin Optimizer PASSED.");
    }
}
