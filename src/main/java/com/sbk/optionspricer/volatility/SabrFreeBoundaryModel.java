package com.sbk.optionspricer.volatility;

/**
 * Formerly advertised as a "free-boundary / density-corrected" SABR that prevents the Hagan expansion from
 * breaking down for deep out-of-the-money strikes. No such correction was ever implemented: the class evaluated
 * the Hagan formula with <em>z</em> clamped to +/-0.999 (which flattens and distorts the wings), a fast log
 * accurate to ~1e-6, and an arbitrary floor of 0.1% volatility.
 *
 * <p>It is now an alias of {@link SabrModel}. A genuinely arbitrage-free SABR needs the Hagan et al. (2014)
 * free-boundary density or a numerical PDE; that is not provided here.
 *
 * @deprecated use {@link SabrModel}
 */
@Deprecated
public final class SabrFreeBoundaryModel {

    private SabrFreeBoundaryModel() {}

    public static double impliedVolatility(double forward, double strike, double expiry,
                                           double alpha, double beta, double rho, double nu) {
        return SabrModel.impliedVolatility(forward, strike, expiry, alpha, beta, rho, nu);
    }
}
