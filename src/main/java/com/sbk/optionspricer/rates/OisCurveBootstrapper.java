package com.sbk.optionspricer.rates;

import java.util.Arrays;

/**
 * Bootstraps a discount curve from par swap rates (or par yields) with a regular payment schedule.
 *
 * <p>A par instrument with fixed rate R, payment dates t_j and accruals a_j satisfies
 * {@code R * sum(a_j * Z(t_j)) + Z(T) = 1}. Each maturity is solved in turn: the unknown pillar discount
 * factor Z(T) also fixes the interpolated discount factors at the payment dates between the previous pillar
 * and T (log-linear, the same interpolation {@link YieldCurve} uses), so the instrument is solved for Z(T)
 * numerically rather than assuming every pillar gap is a single payment period.
 *
 * <p>Maturities up to one payment period pay once, at maturity: {@code Z = 1/(1 + R T)}. Longer maturities pay
 * every {@code 1/paymentsPerYear} years, with a shorter final period when T is not a whole number of periods.
 * Accruals are simple year fractions of the schedule (no day-count or business-day conventions).
 */
public class OisCurveBootstrapper {

    /** Annual-pay OIS (the usual €STR / SOFR swap convention for maturities over one year). */
    public static YieldCurve bootstrap(double[] maturities, double[] swapRates) {
        return bootstrap(maturities, swapRates, 1);
    }

    /**
     * @param maturities     strictly increasing, positive maturities in years
     * @param rates          par rates as decimals (0.03 = 3%); negative rates are allowed
     * @param paymentsPerYear 1 for annual-pay OIS, 2 for semiannual coupon bonds such as US Treasuries
     */
    public static YieldCurve bootstrap(double[] maturities, double[] rates, int paymentsPerYear) {
        if (maturities == null || rates == null || maturities.length != rates.length || maturities.length == 0) {
            throw new IllegalArgumentException("maturities and rates must be non-empty arrays of equal length");
        }
        if (paymentsPerYear < 1) {
            throw new IllegalArgumentException("paymentsPerYear must be at least 1");
        }
        double previous = 0.0;
        for (int i = 0; i < maturities.length; i++) {
            if (!Double.isFinite(maturities[i]) || maturities[i] <= previous) {
                throw new IllegalArgumentException("maturities must be positive and strictly increasing (index " + i + ")");
            }
            if (!Double.isFinite(rates[i])) {
                throw new IllegalArgumentException("rates must be finite (index " + i + ")");
            }
            previous = maturities[i];
        }

        double[] times = new double[maturities.length];
        double[] discountFactors = new double[maturities.length];
        double period = 1.0 / paymentsPerYear;

        for (int n = 0; n < maturities.length; n++) {
            double maturity = maturities[n];
            double rate = rates[n];
            double z;
            if (maturity <= period + 1e-12) {
                z = 1.0 / (1.0 + rate * maturity); // single payment at maturity
                if (!(z > 0.0)) {
                    throw new IllegalArgumentException("rate " + rate + " at maturity " + maturity + " implies a non-positive discount factor");
                }
            } else {
                double previousTime = n == 0 ? 0.0 : times[n - 1];
                double previousZ = n == 0 ? 1.0 : discountFactors[n - 1];
                YieldCurve known = n == 0 ? null : new YieldCurve(Arrays.copyOf(times, n), Arrays.copyOf(discountFactors, n));
                z = solvePillar(maturity, rate, period, previousTime, previousZ, known);
            }
            times[n] = maturity;
            discountFactors[n] = z;
        }
        return new YieldCurve(times, discountFactors);
    }

    /** Solves R * sum(a_j Z(t_j)) + Z(T) - 1 = 0 for Z(T); the left side is increasing in Z(T), so bisection is safe. */
    private static double solvePillar(double maturity, double rate, double period, double previousTime, double previousZ, YieldCurve known) {
        int regular = (int) Math.floor(maturity / period + 1e-9);
        double stub = maturity - regular * period;
        boolean hasStub = stub > 1e-9;

        double lo = 1e-12;
        double hi = 10.0;
        if (residual(lo, maturity, rate, period, regular, hasStub, stub, previousTime, previousZ, known) > 0.0) {
            throw new IllegalArgumentException("rate " + rate + " at maturity " + maturity
                    + " is inconsistent with the shorter maturities (it implies a non-positive discount factor)");
        }
        for (int i = 0; i < 200; i++) {
            double mid = 0.5 * (lo + hi);
            if (residual(mid, maturity, rate, period, regular, hasStub, stub, previousTime, previousZ, known) > 0.0) {
                hi = mid;
            } else {
                lo = mid;
            }
            if (hi - lo <= 1e-16 * mid) {
                break;
            }
        }
        return 0.5 * (lo + hi);
    }

    private static double residual(double zT, double maturity, double rate, double period, int regular, boolean hasStub,
                                   double stub, double previousTime, double previousZ, YieldCurve known) {
        double annuity = 0.0;
        for (int j = 1; j <= regular; j++) {
            annuity += period * discountFactorAt(j * period, zT, maturity, previousTime, previousZ, known);
        }
        if (hasStub) {
            annuity += stub * zT;
        }
        return rate * annuity + zT - 1.0;
    }

    /** Z(t) for t <= maturity: the known curve up to the previous pillar, log-linear towards the unknown Z(T) after it. */
    private static double discountFactorAt(double t, double zT, double maturity, double previousTime, double previousZ, YieldCurve known) {
        if (t >= maturity - 1e-12) {
            return zT;
        }
        if (known != null && t <= previousTime + 1e-12) {
            return known.getDiscountFactor(t);
        }
        double weight = (t - previousTime) / (maturity - previousTime);
        return Math.exp((1.0 - weight) * Math.log(previousZ) + weight * Math.log(zT));
    }
}
