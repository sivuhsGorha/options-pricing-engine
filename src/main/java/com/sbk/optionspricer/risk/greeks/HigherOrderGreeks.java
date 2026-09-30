package com.sbk.optionspricer.risk.greeks;

/**
 * Higher-order sensitivities of the option price.
 *
 * @param vanna sensitivity of Delta to Volatility (dDelta / dVol)
 * @param volga sensitivity of Vega to Volatility (dVega / dVol)
 * @param charm sensitivity of Delta to time decay (dDelta / dTime)
 * @param speed sensitivity of Gamma to Spot (dGamma / dSpot)
 * @param color sensitivity of Gamma to time decay (dGamma / dTime)
 */
public record HigherOrderGreeks(
        double vanna, 
        double volga, 
        double charm, 
        double speed, 
        double color
) {
}
