package com.sbk.optionspricer.risk;

/**
 * Dynamic Margin / Eurex Clearing Initial Margin Optimizer.
 * Analyzes portfolio Greeks and suggests optimal delta-hedge execution quantities
 * to minimize clearinghouse initial margin requirements.
 */
public final class MarginOptimizer {

    public static class OptimizationResult {
        public final double scenarioMargin;
        public final double optimizedMargin;
        public final int recommendedHedgeShares;
        public final double marginReductionPct;

        public OptimizationResult(double scenarioMargin, double optimizedMargin,
                                  int recommendedHedgeShares, double marginReductionPct) {
            this.scenarioMargin = scenarioMargin;
            this.optimizedMargin = optimizedMargin;
            this.recommendedHedgeShares = recommendedHedgeShares;
            this.marginReductionPct = marginReductionPct;
        }
    }

    private MarginOptimizer() {}

    /**
     * Calculates the optimal delta-hedge allocation that minimizes clearing initial margin.
     * 
     * @param netDelta Current net portfolio delta
     * @param netGamma Current net portfolio gamma
     * @param spot Underlying spot price
    * @param scenarioMargin Current clearinghouse scenario margin requirement
     */
    public static OptimizationResult optimizeMargin(double netDelta, double netGamma, double spot, double scenarioMargin) {
        // Ideal delta hedge quantity to flatten directional exposure
        int optimalHedge = (int) Math.round(-netDelta);
        
        // Eurex Clearing margin scaling approximation: Margin ~ k1 * |Delta| * Spot + k2 * |Gamma| * Spot^2
        double gammaMargin = Math.abs(netGamma) * spot * spot * 0.05;
        double unhedgedDeltaMargin = Math.abs(netDelta) * spot * 0.15;

        double optimizedMargin = gammaMargin + Math.abs(netDelta + optimalHedge) * spot * 0.15;
        double reductionPct = ((scenarioMargin - optimizedMargin) / scenarioMargin) * 100.0;

        return new OptimizationResult(scenarioMargin, Math.max(1000.0, optimizedMargin), optimalHedge, Math.max(0.0, reductionPct));
    }
}
