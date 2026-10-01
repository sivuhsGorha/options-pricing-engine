package com.sbk.optionspricer.risk;

/**
 * Dynamic SPAN / Eurex Prisma Initial Margin Optimizer.
 * Analyzes portfolio Greeks and suggests optimal delta-hedge execution quantities
 * to minimize clearinghouse initial margin requirements.
 */
public final class SpanMarginOptimizer {

    public static class OptimizationResult {
        public final double originalMargin;
        public final double optimizedMargin;
        public final int recommendedHedgeShares;
        public final double marginReductionPct;

        public OptimizationResult(double originalMargin, double optimizedMargin,
                                  int recommendedHedgeShares, double marginReductionPct) {
            this.originalMargin = originalMargin;
            this.optimizedMargin = optimizedMargin;
            this.recommendedHedgeShares = recommendedHedgeShares;
            this.marginReductionPct = marginReductionPct;
        }
    }

    private SpanMarginOptimizer() {}

    /**
     * Calculates the optimal delta-hedge allocation that minimizes clearing initial margin.
     * 
     * @param netDelta Current net portfolio delta
     * @param netGamma Current net portfolio gamma
     * @param spot Underlying spot price
     * @param currentMargin Current clearinghouse SPAN margin requirement
     */
    public static OptimizationResult optimizeMargin(double netDelta, double netGamma, double spot, double currentMargin) {
        // Ideal delta hedge quantity to flatten directional exposure
        int optimalHedge = (int) Math.round(-netDelta);
        
        // Eurex Prisma margin scaling approximation: Margin ~ k1 * |Delta| * Spot + k2 * |Gamma| * Spot^2
        double gammaMargin = Math.abs(netGamma) * spot * spot * 0.05;
        double unhedgedDeltaMargin = Math.abs(netDelta) * spot * 0.15;

        double optimizedMargin = gammaMargin + Math.abs(netDelta + optimalHedge) * spot * 0.15;
        double reductionPct = ((currentMargin - optimizedMargin) / currentMargin) * 100.0;

        return new OptimizationResult(currentMargin, Math.max(1000.0, optimizedMargin), optimalHedge, Math.max(0.0, reductionPct));
    }
}
