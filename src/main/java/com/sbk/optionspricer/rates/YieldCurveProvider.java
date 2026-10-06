package com.sbk.optionspricer.rates;

/**
 * Strategy interface for fetching current risk-free yield curves.
 */
public interface YieldCurveProvider {
    YieldCurve getYieldCurve();
}
