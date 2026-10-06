package com.sbk.optionspricer.models.pde;

import com.sbk.optionspricer.OptionParameters;
import com.sbk.optionspricer.OptionType;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Finite-difference pricer for European and American options with a continuous dividend yield and/or
 * discrete cash dividends. This is the single PDE solver in the codebase.
 *
 * <p>The Black-Scholes PDE is solved in x = ln S on a uniform grid: {@code V_tau = 0.5 s^2 V_xx + nu V_x - r V}
 * with {@code nu = r - q - 0.5 s^2}. Design choices, each of which fixes a defect of the earlier version:
 * <ul>
 *   <li><b>Strike on a grid node</b>, and the domain covers both spot and strike (+/- 4.5 sd plus the drift), so
 *       the payoff kink is represented exactly and far strikes are priced from a meaningful grid.</li>
 *   <li><b>Rannacher start-up</b>: the first two Crank-Nicolson steps after maturity (and after each dividend,
 *       where the solution has a new kink) are replaced by four fully implicit half-steps, which damps the
 *       oscillations Crank-Nicolson produces on a non-smooth payoff and restores second-order convergence.</li>
 *   <li><b>Dividend yield</b> enters the drift and the boundary values (it used to be ignored).</li>
 *   <li><b>Exact ex-dividend dates</b>: the time axis is split at each dividend, so the jump
 *       {@code V(t-, S) = V(t+, S - D)} happens at the true date, not at the nearest step. Dividends at or
 *       after expiry (or at/before time 0) have no effect. The grid's lower end is widened by the total
 *       dividends so {@code S - D} stays on the grid.</li>
 *   <li><b>American exercise</b> is enforced inside the linear solve with the Brennan-Schwartz algorithm
 *       (a projected Thomas elimination, exact for the linear complementarity problem when the exercise
 *       region is one-sided: low spot for puts, high spot for calls), not by clipping afterwards.</li>
 *   <li><b>Boundary values</b> are floored at zero (a deep out-of-the-money call used to get a negative upper
 *       boundary) and subtract the present value of the dividends still to come.</li>
 *   <li>The price at the spot is read by cubic interpolation.</li>
 * </ul>
 */
public class DiscreteDividendPricer {

    public static class DiscreteDividend {
        public final double timeToDividend; // Time from t=0 to ex-dividend date (years)
        public final double amount;         // Cash dividend amount D ($)

        public DiscreteDividend(double timeToDividend, double amount) {
            this.timeToDividend = timeToDividend;
            this.amount = amount;
        }
    }

    public static class Workspace {
        public final double[] x;
        public final double[] S;
        public final double[] V;
        public final double[] a;
        public final double[] b;
        public final double[] c;
        public final double[] Z;
        public final double[] cPrime;
        public final double[] tempV;
        public final double[] intrinsic;

        public Workspace(int N) {
            this.x = new double[N + 1];
            this.S = new double[N + 1];
            this.V = new double[N + 1];
            this.a = new double[N + 1];
            this.b = new double[N + 1];
            this.c = new double[N + 1];
            this.Z = new double[N + 1];
            this.cPrime = new double[N + 1];
            this.tempV = new double[N + 1];
            this.intrinsic = new double[N + 1];
        }
    }

    private static final ThreadLocal<Workspace> THREAD_LOCAL_WORKSPACE = ThreadLocal.withInitial(() -> new Workspace(150));

    /** Prices an option, honouring {@code p.dividendYield()} and the discrete cash dividends. */
    public static double price(OptionType type, OptionParameters p, DiscreteDividend[] dividends,
                               int spaceSteps, int timeSteps, boolean isAmerican) {
        if (spaceSteps < 4) {
            throw new IllegalArgumentException("spaceSteps must be at least 4");
        }
        Workspace ws = THREAD_LOCAL_WORKSPACE.get();
        if (ws.x.length < spaceSteps + 1) {
            ws = new Workspace(spaceSteps);
            THREAD_LOCAL_WORKSPACE.set(ws);
        }
        return priceWorkspace(type, p.spot(), p.strike(), p.timeToExpiry(), p.riskFreeRate(), p.dividendYield(), p.volatility(),
                dividends, spaceSteps, timeSteps, isAmerican, ws);
    }

    /** As {@link #priceWorkspace(OptionType, double, double, double, double, double, double, DiscreteDividend[], int, int, boolean, Workspace)} with no dividend yield. */
    public static double priceWorkspace(OptionType type, double spot, double strike, double timeToExpiry, double riskFreeRate, double volatility,
                                        DiscreteDividend[] dividends, int spaceSteps, int timeSteps, boolean isAmerican, Workspace ws) {
        return priceWorkspace(type, spot, strike, timeToExpiry, riskFreeRate, 0.0, volatility, dividends, spaceSteps, timeSteps, isAmerican, ws);
    }

    public static double priceWorkspace(OptionType type, double spot, double strike, double timeToExpiry, double riskFreeRate, double dividendYield,
                                        double volatility, DiscreteDividend[] dividends, int spaceSteps, int timeSteps, boolean isAmerican, Workspace ws) {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (!Double.isFinite(spot) || spot <= 0.0) throw new IllegalArgumentException("spot must be finite and positive");
        if (!Double.isFinite(strike) || strike <= 0.0) throw new IllegalArgumentException("strike must be finite and positive");
        if (!Double.isFinite(timeToExpiry) || timeToExpiry < 0.0) throw new IllegalArgumentException("timeToExpiry must be finite and non-negative");
        if (!Double.isFinite(riskFreeRate) || !Double.isFinite(dividendYield)) throw new IllegalArgumentException("rates must be finite");
        if (!Double.isFinite(volatility) || volatility < 0.0) throw new IllegalArgumentException("volatility must be finite and non-negative");
        if (spaceSteps < 4) throw new IllegalArgumentException("spaceSteps must be at least 4");
        if (timeSteps < 1) throw new IllegalArgumentException("timeSteps must be at least 1");
        if (ws.x.length < spaceSteps + 1) throw new IllegalArgumentException("workspace is smaller than spaceSteps + 1");

        if (timeToExpiry <= 1e-10 || volatility <= 1e-10) {
            return intrinsic(type, spot, strike);
        }
        return new Solver(type, spot, strike, timeToExpiry, riskFreeRate, dividendYield, volatility,
                dividends, spaceSteps, timeSteps, isAmerican, ws).price();
    }

    private static double intrinsic(OptionType type, double s, double k) {
        return type == OptionType.CALL ? Math.max(s - k, 0.0) : Math.max(k - s, 0.0);
    }

    /** One pricing run: holds the grid, coefficients and the dividend schedule. */
    private static final class Solver {
        private final OptionType type;
        private final double strike;
        private final double maturity;
        private final double rate;
        private final double yield;
        private final boolean american;
        private final int n;
        private final int timeSteps;
        private final Workspace ws;
        private final double[] divTimes;
        private final double[] divAmounts;

        private final double xLo;
        private final double dx;
        private final double logSpot;
        // PDE operator L V_i = lo V_{i-1} + mid V_i + up V_{i+1}
        private final double lo, mid, up;

        Solver(OptionType type, double spot, double strike, double maturity, double rate, double yield, double vol,
               DiscreteDividend[] dividends, int n, int timeSteps, boolean american, Workspace ws) {
            this.type = type;
            this.strike = strike;
            this.maturity = maturity;
            this.rate = rate;
            this.yield = yield;
            this.american = american;
            this.n = n;
            this.timeSteps = timeSteps;
            this.ws = ws;

            // Only dividends strictly inside (0, T) matter; keep them sorted by date.
            DiscreteDividend[] valid = dividends == null ? new DiscreteDividend[0] : Arrays.stream(dividends)
                    .filter(d -> d != null && Double.isFinite(d.timeToDividend) && Double.isFinite(d.amount)
                            && d.amount > 0.0 && d.timeToDividend > 1e-12 && d.timeToDividend < maturity - 1e-12)
                    .sorted(Comparator.comparingDouble(d -> d.timeToDividend))
                    .toArray(DiscreteDividend[]::new);
            this.divTimes = new double[valid.length];
            this.divAmounts = new double[valid.length];
            double totalDividends = 0.0;
            for (int i = 0; i < valid.length; i++) {
                divTimes[i] = valid[i].timeToDividend;
                divAmounts[i] = valid[i].amount;
                totalDividends += valid[i].amount;
            }

            double nu = rate - yield - 0.5 * vol * vol;
            double halfWidth = 4.5 * vol * Math.sqrt(maturity) + Math.abs(nu) * maturity;
            this.logSpot = Math.log(spot);
            double logStrike = Math.log(strike);
            double hi = Math.max(logSpot, logStrike) + halfWidth;
            double low = Math.min(logSpot, logStrike) - halfWidth;
            if (totalDividends > 0.0) {
                double sLow = Math.exp(low);
                low = Math.log(Math.max(sLow - totalDividends, sLow * 0.05)); // keep S - D on the grid
            }
            double step = (hi - low) / n;
            double shift = logStrike - (low + Math.round((logStrike - low) / step) * step); // put the strike on a node
            this.xLo = low + shift;
            this.dx = step;

            double p = 0.5 * vol * vol / (dx * dx);
            double q = 0.5 * nu / dx;
            this.lo = p - q;
            this.mid = -(2.0 * p + rate);
            this.up = p + q;
        }

        double price() {
            double[] x = ws.x;
            double[] s = ws.S;
            double[] v = ws.V;
            double[] g = ws.intrinsic;
            for (int i = 0; i <= n; i++) {
                x[i] = xLo + i * dx;
                s[i] = Math.exp(x[i]);
                g[i] = intrinsic(type, s[i], strike);
                v[i] = g[i];
            }

            // Walk the time axis backwards from maturity, one segment between consecutive dividend dates at a time.
            double now = maturity;
            for (int segment = divTimes.length; segment >= 0; segment--) {
                double segmentStart = segment == 0 ? 0.0 : divTimes[segment - 1];
                double length = now - segmentStart;
                int steps = Math.max(1, (int) Math.round(timeSteps * length / maturity));
                double dt = length / steps;
                int implicitStartup = Math.min(2, steps);
                for (int k = 0; k < steps; k++) {
                    double levelAfter = k == steps - 1 ? segmentStart : now - (k + 1) * dt;
                    if (k < implicitStartup) {
                        // Rannacher: two fully implicit half-steps in place of one Crank-Nicolson step.
                        double mid1 = 0.5 * (now - k * dt + levelAfter);
                        advance(1.0, 0.5 * dt, mid1);
                        advance(1.0, 0.5 * dt, levelAfter);
                    } else {
                        advance(0.5, dt, levelAfter);
                    }
                }
                now = segmentStart;
                if (segment > 0) {
                    applyDividend(divAmounts[segment - 1]);
                }
            }
            return Math.max(0.0, interpolate(v, logSpot));
        }

        /** One theta-step backwards in time to {@code level} (time from today); theta 0.5 = Crank-Nicolson, 1 = implicit. */
        private void advance(double theta, double h, double level) {
            double[] v = ws.V;
            double[] a = ws.a;
            double[] b = ws.b;
            double[] c = ws.c;
            double[] z = ws.Z;

            double aLow = -theta * h * lo;
            double aMain = 1.0 - theta * h * mid;
            double aUp = -theta * h * up;
            double bLow = (1.0 - theta) * h * lo;
            double bMain = 1.0 + (1.0 - theta) * h * mid;
            double bUp = (1.0 - theta) * h * up;
            for (int i = 1; i < n; i++) {
                z[i] = bLow * v[i - 1] + bMain * v[i] + bUp * v[i + 1];
                a[i] = aLow;
                b[i] = aMain;
                c[i] = aUp;
            }

            // Dirichlet boundaries: discounted/forward-adjusted intrinsic value, never negative.
            double tau = maturity - level;
            double pvDividends = 0.0;
            for (int j = 0; j < divTimes.length; j++) {
                if (divTimes[j] > level + 1e-12) {
                    pvDividends += divAmounts[j] * Math.exp(-rate * (divTimes[j] - level));
                }
            }
            double discountedStrike = strike * Math.exp(-rate * tau);
            double yieldDiscount = Math.exp(-yield * tau);
            double sLow = ws.S[0];
            double sHigh = ws.S[n];
            double lower;
            double upper;
            if (type == OptionType.CALL) {
                lower = 0.0;
                upper = Math.max(sHigh * yieldDiscount - pvDividends - discountedStrike, 0.0);
                if (american) upper = Math.max(upper, sHigh - strike);
            } else {
                lower = Math.max(discountedStrike - Math.max(sLow * yieldDiscount - pvDividends, 0.0), 0.0);
                upper = 0.0;
                if (american) lower = Math.max(lower, strike - sLow);
            }
            z[0] = lower;
            z[n] = upper;
            a[0] = 0.0; b[0] = 1.0; c[0] = 0.0;
            a[n] = 0.0; b[n] = 1.0; c[n] = 0.0;

            if (american) {
                solveWithConstraint(a, b, c, z, ws.intrinsic, v);
            } else {
                ThomasAlgorithm.solve(a, b, c, z, n + 1, ws.cPrime);
                System.arraycopy(z, 0, v, 0, n + 1);
            }
        }

        /**
         * Brennan-Schwartz: solves A v = z subject to v >= g for a tridiagonal M-matrix when the exercise
         * region is one-sided. Puts exercise at low spot (eliminate from the top, sweep upwards); calls exercise
         * at high spot (mirror image).
         */
        private void solveWithConstraint(double[] a, double[] b, double[] c, double[] z, double[] g, double[] out) {
            double[] bp = ws.cPrime;
            double[] zp = ws.tempV;
            if (type == OptionType.PUT) {
                bp[n] = b[n];
                zp[n] = z[n];
                for (int i = n - 1; i >= 0; i--) {
                    double f = c[i] / bp[i + 1];
                    bp[i] = b[i] - f * a[i + 1];
                    zp[i] = z[i] - f * zp[i + 1];
                }
                out[0] = Math.max(g[0], zp[0] / bp[0]);
                for (int i = 1; i <= n; i++) {
                    out[i] = Math.max(g[i], (zp[i] - a[i] * out[i - 1]) / bp[i]);
                }
            } else {
                bp[0] = b[0];
                zp[0] = z[0];
                for (int i = 1; i <= n; i++) {
                    double f = a[i] / bp[i - 1];
                    bp[i] = b[i] - f * c[i - 1];
                    zp[i] = z[i] - f * zp[i - 1];
                }
                out[n] = Math.max(g[n], zp[n] / bp[n]);
                for (int i = n - 1; i >= 0; i--) {
                    out[i] = Math.max(g[i], (zp[i] - c[i] * out[i + 1]) / bp[i]);
                }
            }
        }

        /** Jump condition at an ex-dividend date: V(t-, S) = V(t+, S - D); an American holder may also exercise just before. */
        private void applyDividend(double amount) {
            double[] s = ws.S;
            double[] v = ws.V;
            double[] temp = ws.tempV;
            for (int i = 0; i <= n; i++) {
                double after = s[i] - amount;
                temp[i] = after <= s[0] ? v[0] : Math.max(0.0, interpolate(v, Math.log(after)));
            }
            System.arraycopy(temp, 0, v, 0, n + 1);
            if (american) {
                for (int i = 0; i <= n; i++) {
                    v[i] = Math.max(v[i], ws.intrinsic[i]);
                }
            }
        }

        /** Cubic (4-point Lagrange) interpolation of grid values at log-spot {@code xq}. */
        private double interpolate(double[] values, double xq) {
            double u = (xq - xLo) / dx;
            int i = (int) Math.floor(u);
            i = Math.max(1, Math.min(n - 2, i));
            double t = u - i;
            double wm1 = -t * (t - 1.0) * (t - 2.0) / 6.0;
            double w0 = (t + 1.0) * (t - 1.0) * (t - 2.0) / 2.0;
            double w1 = -(t + 1.0) * t * (t - 2.0) / 2.0;
            double w2 = (t + 1.0) * t * (t - 1.0) / 6.0;
            return wm1 * values[i - 1] + w0 * values[i] + w1 * values[i + 1] + w2 * values[i + 2];
        }
    }
}
