package com.sbk.optionspricer.volatility;

import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.TimeConventions;
import com.sbk.optionspricer.VolatilitySurfaceCalibrator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * Fits a parametric implied-volatility surface to option quotes.
 *
 * <p>Step 1 ({@link #extractPoints}) turns chains into market points: for each quote with a two-sided market,
 * the out-of-the-money side only (puts below the forward, calls at or above it), inverted from the mid price
 * with {@link VolatilitySurfaceCalibrator}. Quotes that cannot be inverted are counted, not guessed.
 *
 * <p>Step 2 fits a model by least squares on implied volatility with {@link NelderMead}:
 * <ul>
 *   <li>{@link #fitSsvi}: one global (eta, gamma, rho) with the at-the-money total variance theta_T of each
 *       expiry taken from the data (interpolated at k = 0), the standard SSVI calibration;</li>
 *   <li>{@link #fitSabr}: Hagan SABR (alpha, rho, nu) per expiry with beta fixed at {@link #SABR_BETA}.</li>
 * </ul>
 * Both report the fitted grid, every market point with its model value, the RMSE in volatility, the parameters,
 * and warnings (an expiry with too few quotes, a non-increasing theta, a fit that hit the iteration cap). SSVI
 * also reports whether the closed-form static no-arbitrage conditions hold for the fitted parameters.
 */
public final class SurfaceFitter {

    public static final double SABR_BETA = 0.5;
    static final int MIN_POINTS_PER_EXPIRY = 3;
    static final int GRID_STRIKES = 15;
    private static final int MAX_ITERATIONS = 4000;
    private static final double TOLERANCE = 1e-14;

    public record MarketPoint(LocalDate expiry, double timeToExpiry, double strike, double forward, OptionType type, double marketVol) {}

    public record FittedPoint(double timeToExpiry, double strike, double marketVol, double modelVol) {}

    /**
     * @param expiries       expiries of the grid rows (may include interpolated rows, see {@link #densify})
     * @param fittedExpiries the expiries that carry market quotes and were actually fitted
     */
    public record Fit(String model, double[] strikes, double[] expiries, double[][] vols, List<FittedPoint> points,
                      double rmse, int quotesUsed, Map<String, Double> parameters, boolean noArbitrageConditionsHold,
                      List<String> warnings, double[] fittedExpiries) {}

    /**
     * Adds grid rows between the fitted expiries so the surface draws as a sheet rather than a few ribbons.
     * Rows are interpolated linearly in total variance (vol^2 x T) between the two neighbouring fitted expiries,
     * which is how a surface is interpolated in time in practice; the fitted rows themselves are kept exactly and
     * the market points are untouched. A fit with a single expiry is returned unchanged.
     */
    public static Fit densify(Fit fit, int rows) {
        if (fit == null || fit.expiries().length < 2 || rows <= fit.expiries().length) {
            return fit;
        }
        double[] src = fit.expiries();
        double first = src[0], last = src[src.length - 1];
        java.util.TreeSet<Double> ts = new java.util.TreeSet<>();
        for (double t : src) ts.add(t);
        for (int i = 0; i < rows; i++) ts.add(first + (last - first) * i / (rows - 1));
        double[] expiries = ts.stream().mapToDouble(Double::doubleValue).toArray();
        double[][] vols = new double[expiries.length][fit.strikes().length];
        for (int i = 0; i < expiries.length; i++) {
            double t = expiries[i];
            int hi = 0;
            while (hi < src.length - 1 && src[hi] < t) hi++;
            int lo = Math.max(0, hi - 1);
            if (src[hi] == t || hi == lo) {
                int exact = src[hi] == t ? hi : lo;
                vols[i] = fit.vols()[exact].clone();
                continue;
            }
            double weight = (t - src[lo]) / (src[hi] - src[lo]);
            for (int j = 0; j < fit.strikes().length; j++) {
                double wLo = fit.vols()[lo][j] * fit.vols()[lo][j] * src[lo];
                double wHi = fit.vols()[hi][j] * fit.vols()[hi][j] * src[hi];
                vols[i][j] = Math.sqrt((wLo + weight * (wHi - wLo)) / t);
            }
        }
        return new Fit(fit.model(), fit.strikes(), expiries, vols, fit.points(), fit.rmse(), fit.quotesUsed(),
                fit.parameters(), fit.noArbitrageConditionsHold(), fit.warnings(), fit.fittedExpiries());
    }

    public record Extraction(List<MarketPoint> points, int quotesSkipped, List<String> warnings) {}

    private SurfaceFitter() {
    }

    // ------------------------------------------------------------------ extraction

    public static Extraction extractPoints(List<OptionChain> chains, double riskFreeRate, double dividendYield, LocalDate asOf) {
        if (chains == null || asOf == null) {
            throw new IllegalArgumentException("chains and asOf must not be null");
        }
        List<MarketPoint> points = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int skipped = 0;
        for (OptionChain chain : chains) {
            double t = TimeConventions.yearFraction(asOf, chain.expiry());
            if (!(t > 0.0)) {
                warnings.add("expiry " + chain.expiry() + " is not after " + asOf + "; chain ignored");
                continue;
            }
            double forward = chain.spot() * Math.exp((riskFreeRate - dividendYield) * t);
            for (OptionQuote quote : chain.quotes()) {
                boolean otm = quote.type() == OptionType.PUT ? quote.strike() < forward : quote.strike() >= forward;
                if (!otm) {
                    continue; // the in-the-money side carries the same information with a worse spread
                }
                if (!(quote.bid() > 0.0) || !(quote.ask() >= quote.bid())) {
                    skipped++; // no two-sided market: the mid is not a price anyone would trade
                    continue;
                }
                OptionalDouble iv = VolatilitySurfaceCalibrator.impliedVolatility(chain.spot(), quote.strike(), t,
                        riskFreeRate, dividendYield, quote.type(), quote.midPrice());
                if (iv.isEmpty() || !(iv.getAsDouble() > 0.01) || !(iv.getAsDouble() < 3.0)) {
                    skipped++;
                    continue;
                }
                points.add(new MarketPoint(chain.expiry(), t, quote.strike(), forward, quote.type(), iv.getAsDouble()));
            }
        }
        return new Extraction(List.copyOf(points), skipped, List.copyOf(warnings));
    }

    // ------------------------------------------------------------------ SSVI

    public static Fit fitSsvi(List<MarketPoint> points) {
        List<String> warnings = new ArrayList<>();
        TreeMap<Double, List<MarketPoint>> byExpiry = groupUsable(points, warnings);
        double[] expiries = byExpiry.keySet().stream().mapToDouble(Double::doubleValue).toArray();
        double[] theta = new double[expiries.length];
        double[] forwards = new double[expiries.length];
        for (int i = 0; i < expiries.length; i++) {
            List<MarketPoint> slice = byExpiry.get(expiries[i]);
            forwards[i] = slice.get(0).forward();
            double atmVol = atmVol(slice, warnings);
            theta[i] = atmVol * atmVol * expiries[i];
            if (i > 0 && theta[i] <= theta[i - 1]) {
                warnings.add(String.format(java.util.Locale.ROOT,
                        "ATM total variance is not increasing between T=%.3f and T=%.3f (calendar arbitrage in the quotes)", expiries[i - 1], expiries[i]));
            }
        }
        Map<Double, Integer> expiryIndex = new LinkedHashMap<>();
        for (int i = 0; i < expiries.length; i++) expiryIndex.put(expiries[i], i);
        List<MarketPoint> used = byExpiry.values().stream().flatMap(List::stream).toList();

        java.util.function.ToDoubleFunction<double[]> loss = p -> {
            SsviApproximation.SsviParams params = ssviParams(p);
            double sum = 0.0;
            for (MarketPoint mp : used) {
                int i = expiryIndex.get(mp.timeToExpiry());
                double model = ssviVol(mp, theta[i], params);
                double d = model - mp.marketVol();
                sum += d * d;
            }
            return sum / used.size();
        };

        NelderMead.Result best = null;
        for (double rhoStart : new double[]{-0.4, 0.2}) {
            double[] start = {0.0, logit(0.6), atanh(rhoStart)};
            NelderMead.Result r = NelderMead.minimize(loss, start, new double[]{0.5, 0.5, 0.5}, MAX_ITERATIONS, TOLERANCE);
            if (best == null || r.value() < best.value()) best = r;
        }
        if (!best.converged()) {
            warnings.add("SSVI fit reached the iteration cap; parameters are the best found, not a converged optimum");
        }
        SsviApproximation.SsviParams params = ssviParams(best.point());
        if (Math.abs(params.rho) > 0.99) {
            warnings.add("SSVI rho sits at its bound: the quotes carry little skew information");
        }
        if (params.eta < 1e-3) {
            warnings.add("SSVI eta ~ 0: the quotes show no smile, so the fitted surface is flat in moneyness");
        }

        double[] strikes = strikeGrid(used);
        double[][] vols = new double[expiries.length][strikes.length];
        for (int i = 0; i < expiries.length; i++) {
            for (int j = 0; j < strikes.length; j++) {
                double k = Math.log(strikes[j] / forwards[i]);
                vols[i][j] = Math.sqrt(SsviApproximation.totalVariance(k, theta[i], params) / expiries[i]);
            }
        }
        List<FittedPoint> fitted = new ArrayList<>(used.size());
        double sumSq = 0.0;
        for (MarketPoint mp : used) {
            double model = ssviVol(mp, theta[expiryIndex.get(mp.timeToExpiry())], params);
            fitted.add(new FittedPoint(mp.timeToExpiry(), mp.strike(), mp.marketVol(), model));
            sumSq += (model - mp.marketVol()) * (model - mp.marketVol());
        }
        Map<String, Double> parameters = new LinkedHashMap<>();
        parameters.put("eta", params.eta);
        parameters.put("gamma", params.gamma);
        parameters.put("rho", params.rho);
        for (int i = 0; i < expiries.length; i++) parameters.put(String.format(java.util.Locale.ROOT, "theta[%.3f]", expiries[i]), theta[i]);
        boolean thetaIncreasing = true;
        for (int i = 1; i < theta.length; i++) thetaIncreasing &= theta[i] > theta[i - 1];
        return new Fit("SSVI", strikes, expiries, vols, List.copyOf(fitted), Math.sqrt(sumSq / used.size()), used.size(),
                parameters, params.satisfiesStaticNoArbitrageConditions() && thetaIncreasing, List.copyOf(warnings), expiries);
    }

    private static double ssviVol(MarketPoint mp, double theta, SsviApproximation.SsviParams params) {
        double k = Math.log(mp.strike() / mp.forward());
        return Math.sqrt(SsviApproximation.totalVariance(k, theta, params) / mp.timeToExpiry());
    }

    /** Unconstrained (a, b, c) to eta = e^a > 0, gamma = 0.5 sigmoid(b) in (0, 0.5), rho = tanh(c) in (-1, 1). */
    private static SsviApproximation.SsviParams ssviParams(double[] p) {
        double eta = Math.exp(clamp(p[0], -10, 10));
        double gamma = 0.5 / (1.0 + Math.exp(-clamp(p[1], -30, 30)));
        double rho = Math.tanh(clamp(p[2], -10, 10));
        return new SsviApproximation.SsviParams(eta, Math.max(gamma, 1e-6), Math.max(-0.999999, Math.min(0.999999, rho)));
    }

    // ------------------------------------------------------------------ SABR

    public static Fit fitSabr(List<MarketPoint> points) {
        List<String> warnings = new ArrayList<>();
        TreeMap<Double, List<MarketPoint>> byExpiry = groupUsable(points, warnings);
        double[] expiries = byExpiry.keySet().stream().mapToDouble(Double::doubleValue).toArray();
        double[][] fittedParams = new double[expiries.length][];
        double[] forwards = new double[expiries.length];
        Map<String, Double> parameters = new LinkedHashMap<>();
        parameters.put("beta", SABR_BETA);
        List<MarketPoint> used = new ArrayList<>();
        for (int i = 0; i < expiries.length; i++) {
            List<MarketPoint> slice = byExpiry.get(expiries[i]);
            double t = expiries[i];
            double f = slice.get(0).forward();
            forwards[i] = f;
            double atm = atmVol(slice, warnings);
            java.util.function.ToDoubleFunction<double[]> loss = p -> {
                double[] s = sabrParams(p);
                double sum = 0.0;
                for (MarketPoint mp : slice) {
                    double d = SabrModel.impliedVolatility(f, mp.strike(), t, s[0], SABR_BETA, s[1], s[2]) - mp.marketVol();
                    sum += d * d;
                }
                return sum / slice.size();
            };
            double[] start = {Math.log(atm * Math.pow(f, 1.0 - SABR_BETA)), atanh(-0.3), Math.log(0.5)};
            NelderMead.Result r = NelderMead.minimize(loss, start, new double[]{0.3, 0.5, 0.5}, MAX_ITERATIONS, TOLERANCE);
            if (!r.converged()) {
                warnings.add(String.format(java.util.Locale.ROOT, "SABR fit for T=%.3f reached the iteration cap", t));
            }
            fittedParams[i] = sabrParams(r.point());
            if (Math.abs(fittedParams[i][1]) > 0.99) {
                warnings.add(String.format(java.util.Locale.ROOT, "SABR rho sits at its bound for T=%.3f: the quotes carry little skew information", t));
            }
            String tag = String.format(java.util.Locale.ROOT, "[%.3f]", t);
            parameters.put("alpha" + tag, fittedParams[i][0]);
            parameters.put("rho" + tag, fittedParams[i][1]);
            parameters.put("nu" + tag, fittedParams[i][2]);
            used.addAll(slice);
        }

        double[] strikes = strikeGrid(used);
        double[][] vols = new double[expiries.length][strikes.length];
        for (int i = 0; i < expiries.length; i++) {
            double[] s = fittedParams[i];
            for (int j = 0; j < strikes.length; j++) {
                vols[i][j] = SabrModel.impliedVolatility(forwards[i], strikes[j], expiries[i], s[0], SABR_BETA, s[1], s[2]);
            }
        }
        List<FittedPoint> fitted = new ArrayList<>(used.size());
        double sumSq = 0.0;
        for (int i = 0; i < expiries.length; i++) {
            double[] s = fittedParams[i];
            for (MarketPoint mp : byExpiry.get(expiries[i])) {
                double model = SabrModel.impliedVolatility(forwards[i], mp.strike(), expiries[i], s[0], SABR_BETA, s[1], s[2]);
                fitted.add(new FittedPoint(mp.timeToExpiry(), mp.strike(), mp.marketVol(), model));
                sumSq += (model - mp.marketVol()) * (model - mp.marketVol());
            }
        }
        warnings.add("Hagan SABR is an asymptotic expansion with no closed-form no-arbitrage guarantee");
        return new Fit("SABR", strikes, expiries, vols, List.copyOf(fitted), Math.sqrt(sumSq / used.size()), used.size(),
                parameters, false, List.copyOf(warnings), expiries);
    }

    /** Unconstrained (a, c, d) to alpha = e^a, rho = tanh(c), nu = e^d. */
    private static double[] sabrParams(double[] p) {
        return new double[]{
                Math.exp(clamp(p[0], -10, 10)),
                Math.max(-0.999999, Math.min(0.999999, Math.tanh(clamp(p[1], -10, 10)))),
                Math.exp(clamp(p[2], -10, 5))
        };
    }

    // ------------------------------------------------------------------ shared

    private static TreeMap<Double, List<MarketPoint>> groupUsable(List<MarketPoint> points, List<String> warnings) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("no market points: nothing to fit");
        }
        TreeMap<Double, List<MarketPoint>> byExpiry = new TreeMap<>();
        for (MarketPoint mp : points) {
            byExpiry.computeIfAbsent(mp.timeToExpiry(), t -> new ArrayList<>()).add(mp);
        }
        byExpiry.entrySet().removeIf(e -> {
            if (e.getValue().size() < MIN_POINTS_PER_EXPIRY) {
                warnings.add(String.format(java.util.Locale.ROOT, "expiry T=%.3f has only %d usable quotes (minimum %d); excluded",
                        e.getKey(), e.getValue().size(), MIN_POINTS_PER_EXPIRY));
                return true;
            }
            return false;
        });
        if (byExpiry.isEmpty()) {
            throw new IllegalArgumentException("no expiry has at least " + MIN_POINTS_PER_EXPIRY + " usable quotes");
        }
        for (List<MarketPoint> slice : byExpiry.values()) {
            slice.sort((a, b) -> Double.compare(a.strike(), b.strike()));
        }
        return byExpiry;
    }

    /** ATM volatility of one expiry: linear interpolation in log-moneyness at k = 0, or the nearest quote if 0 is outside the range. */
    private static double atmVol(List<MarketPoint> slice, List<String> warnings) {
        double prevK = Double.NaN, prevVol = Double.NaN;
        for (MarketPoint mp : slice) {
            double k = Math.log(mp.strike() / mp.forward());
            if (k == 0.0) return mp.marketVol();
            if (!Double.isNaN(prevK) && prevK < 0.0 && k > 0.0) {
                return prevVol + (mp.marketVol() - prevVol) * (0.0 - prevK) / (k - prevK);
            }
            prevK = k;
            prevVol = mp.marketVol();
        }
        MarketPoint nearest = slice.get(0);
        for (MarketPoint mp : slice) {
            if (Math.abs(Math.log(mp.strike() / mp.forward())) < Math.abs(Math.log(nearest.strike() / nearest.forward()))) nearest = mp;
        }
        warnings.add(String.format(java.util.Locale.ROOT, "expiry T=%.3f has no quotes straddling the forward; ATM taken from strike %.2f",
                slice.get(0).timeToExpiry(), nearest.strike()));
        return nearest.marketVol();
    }

    /** Common strike grid: the quoted strike range, clipped to 75%-125% of the forwards, GRID_STRIKES points. */
    private static double[] strikeGrid(List<MarketPoint> used) {
        double minK = Double.MAX_VALUE, maxK = 0.0, minF = Double.MAX_VALUE, maxF = 0.0;
        for (MarketPoint mp : used) {
            minK = Math.min(minK, mp.strike());
            maxK = Math.max(maxK, mp.strike());
            minF = Math.min(minF, mp.forward());
            maxF = Math.max(maxF, mp.forward());
        }
        double lo = Math.max(minK, 0.75 * minF);
        double hi = Math.min(maxK, 1.25 * maxF);
        if (!(hi > lo)) {
            lo = 0.8 * minF;
            hi = 1.2 * maxF;
        }
        double[] grid = new double[GRID_STRIKES];
        for (int j = 0; j < GRID_STRIKES; j++) grid[j] = lo + (hi - lo) * j / (GRID_STRIKES - 1);
        return grid;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double logit(double p) {
        return Math.log(p / (1.0 - p));
    }

    private static double atanh(double x) {
        return 0.5 * Math.log((1.0 + x) / (1.0 - x));
    }
}
