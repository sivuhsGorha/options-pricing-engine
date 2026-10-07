package com.sbk.optionspricer.risk.greeks;

import com.sbk.optionspricer.BlackScholesPricer;
import com.sbk.optionspricer.NormalDistribution;
import com.sbk.optionspricer.OptionParameters;
import com.sbk.optionspricer.OptionType;

/**
 * Closed-form analytical higher-order Greeks. Cheap and deterministic, but not allocation-free:
 * each call returns a new {@code HigherOrderGreeks} record.
 */
public class AnalyticalHigherGreeks {

    public static HigherOrderGreeks calculate(OptionType type, OptionParameters p) {
        if (p.timeToExpiry() <= 1e-10 || p.volatility() <= 1e-10) {
            return new HigherOrderGreeks(0, 0, 0, 0, 0);
        }

        double s = p.spot();
        double k = p.strike();
        double t = p.timeToExpiry();
        double r = p.riskFreeRate();
        double q = p.dividendYield();
        double v = p.volatility();

        double sqrtT = Math.sqrt(t);

        double d1 = (Math.log(s / k) + (r - q + 0.5 * v * v) * t) / (v * sqrtT);
        double d2 = d1 - v * sqrtT;

        double pdfD1 = NormalDistribution.pdf(d1);
        double cdfD1 = NormalDistribution.cdf(d1);
        double cdfMinusD1 = NormalDistribution.cdf(-d1);

        double discQ = Math.exp(-q * t);

        // Base Greeks needed for higher order derivations
        double gamma = (discQ * pdfD1) / (s * v * sqrtT);
        double vega = s * discQ * pdfD1 * sqrtT;

        // 1. Vanna (dDelta / dVol)
        double vanna = -discQ * pdfD1 * (d2 / v);

        // 2. Volga (dVega / dVol)
        double volga = vega * (d1 * d2) / v;

        // 3. Speed (dGamma / dSpot)
        double speed = -(gamma / s) * ((d1 / (v * sqrtT)) + 1.0);

        // 4. Charm (dDelta / dT) - Note: represents decay, so it's -dDelta/dT
        double term1 = 2.0 * (r - q) * t - d2 * v * sqrtT;
        double term2 = 2.0 * t * v * sqrtT;
        double charm;
        if (type == OptionType.CALL) {
            charm = q * discQ * cdfD1 - discQ * pdfD1 * (term1 / term2);
        } else {
            charm = -q * discQ * cdfMinusD1 - discQ * pdfD1 * (term1 / term2);
        }

        // 5. Color (dGamma / dT) - Note: represents decay, so it's -dGamma/dT
        double color = -discQ * (pdfD1 / (2.0 * s * t * v * sqrtT)) *
                       (2.0 * q * t + 1.0 + ((2.0 * (r - q) * t - d2 * v * sqrtT) / (v * sqrtT)) * d1);

        return new HigherOrderGreeks(vanna, volga, charm, speed, color);
    }
}
