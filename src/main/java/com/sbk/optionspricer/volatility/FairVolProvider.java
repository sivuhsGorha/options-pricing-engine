package com.sbk.optionspricer.volatility;

import java.time.LocalDate;
import java.util.OptionalDouble;

/**
 * Source of independent fair volatility estimates (e.g. realized volatility forecasts,
 * HAR-RV, or econometric EWMA models) to distinguish genuine variance risk premium from surface fitting noise.
 */
@FunctionalInterface
public interface FairVolProvider {

    /**
     * Fair volatility estimate for a given strike and expiry horizon.
     *
     * @param symbol       the underlying symbol (e.g. "SPY")
     * @param expiry       contract expiry date
     * @param strike       option strike price
     * @param timeToExpiry annualised time to expiry in years
     * @return annualised fair volatility fraction (e.g. 0.18 for 18%), or empty if insufficient history
     */
    OptionalDouble fairVol(String symbol, LocalDate expiry, double strike, double timeToExpiry);
}
