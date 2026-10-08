package com.sbk.optionspricer.execution;

import java.time.Instant;
import java.util.List;

/**
 * A mark-to-market of the book at one instant: every position's mark, Greeks and unrealised P&L, with where
 * each number came from, plus the portfolio totals.
 *
 * @param spotSource  the spot feed and status the marks used (e.g. {@code FINNHUB/DELAYED}), or {@code CHAIN/...}
 *                    when the option chain's own underlying price had to be used
 */
public record Valuation(Instant asOf, double spot, String spotSource, List<PositionValuation> positions,
                        double unrealizedPnl, double realizedPnl, double netDelta, double netGamma, double netVega,
                        double netTheta, double netRho, List<String> warnings) {

    /**
     * @param markSource {@code MID} (two-sided quote), {@code MODEL} (Black-Scholes at the surface vol, no two-sided
     *                   quote), {@code SPOT} (a stock), {@code INTRINSIC} (an expired contract) or {@code NONE}
     * @param volSource  the fitted model's name ({@code SVI}, {@code SSVI}), {@code MARKET_IV} (inverted from the quote
     *                   mid), {@code FEED_IV} (the feed's own figure), {@code NONE} (Greeks could not be computed) or
     *                   {@code EXPIRED}
     * @param delta      per unit of the underlying for one contract; gamma per unit, vega per 1.0 vol, theta per year,
     *                   rho per 1.0 rate; NaN when no volatility source exists
     */
    public record PositionValuation(String symbol, String kind, int quantity, int multiplier, double mark, String markSource,
                                    double averageCost, double unrealizedPnl, double impliedVol, String volSource,
                                    double delta, double gamma, double vega, double theta, double rho, boolean expired, String note) {}
}
