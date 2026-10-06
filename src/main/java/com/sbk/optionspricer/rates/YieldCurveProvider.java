package com.sbk.optionspricer.rates;

/**
 * Strategy interface for fetching current risk-free yield curves.
 */
public interface YieldCurveProvider {
    YieldCurve getYieldCurve();

    /**
     * False when the shape of the curve is generated rather than quoted, so callers can refuse to
     * price or risk-report off it without noticing.
     */
    default boolean isMarketData() {
        return true;
    }
}
