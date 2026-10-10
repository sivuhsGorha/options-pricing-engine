# Models and Numerical Methods (RESEARCH.md)

The mathematics behind each implemented class, with the limits of each method stated. Section 6 lists models
that are discussed in the literature but not implemented here.

---

## 1. Black-Scholes-Merton and Greeks

`BlackScholesPricer` prices European calls and puts with continuous dividend yield q. Put-call parity
`C - P = S e^{-qT} - K e^{-rT}` is checked in tests to ~1e-14. `Greeks` gives delta, gamma, vega, theta and
rho; `risk/greeks/AnalyticalHigherGreeks` gives vanna, volga, charm, speed and color in closed form.

`NormalDistribution.cdf` is computed from erfc so the lower tail keeps relative accuracy: a series for
|z| < 2 and a continued fraction for |z| >= 2, each accurate to a few ulps. `FastMath` holds low-precision
approximations (exp ~1.6e-7 relative, log ~1.1e-6, A&S 26.2.17 CDF ~7.4e-8) with measured error bounds; they
are not used where a value feeds a price. `VectorBlackScholesPricer` and `SimdMath` use the incubator Vector
API for element-wise arithmetic; exp and log are not vectorised.

**Implied volatility** (`ImpliedVolatilitySolver`): the price is monotone in volatility, so the root is
bracketed in [1e-6, 5] and found by safeguarded Newton (bisect when a Newton step leaves the bracket).
Convergence is judged on volatility (step < 1e-13), not on price, because a price tolerance leaves an error of
tolerance / vega, which is large for short-dated and far-out-of-the-money options. Prices outside the
no-arbitrage bounds return no result. It is a European model: American prices fed to it would attribute the
early-exercise premium to volatility.


---

## 2. American options

**Trinomial tree** (`models/tree/AmericanTreePricer`, `TrinomialTreeParameters`): recombining tree matching
the first two moments of log returns with spacing `dx = sigma sqrt(3 dt)`,
`V_ij = max(intrinsic, e^{-r dt}(p_u V_{i+1,j+1} + p_m V_{i+1,j} + p_d V_{i+1,j-1}))`. Parameters that give a
negative probability (large drift, low vol, few steps) are rejected rather than priced. Memory is O(steps).
Continuous dividend yield only.

**Finite differences** (`models/pde/DiscreteDividendPricer`):
the single PDE solver. Black-Scholes in x = ln S, `V_tau = 1/2 sigma^2 V_xx + nu V_x - r V`,
`nu = r - q - sigma^2/2`, on a uniform grid with the strike on a node and the domain covering spot and strike
+/- 4.5 standard deviations plus drift. Crank-Nicolson time stepping with **Rannacher start-up** (four fully
implicit half-steps after maturity and after each dividend) to damp the oscillations CN produces on a kinked
payoff. **American exercise** by the Brennan-Schwartz projected Thomas elimination inside the linear solve
(exact for the one-sided LCP). **Discrete cash dividends** at exact ex-dates: the time axis is split at each
date and `V(t-, S) = V(t+, S - D)` is applied, with the grid widened so `S - D` stays on it. Boundaries are
floored at zero and net of dividends still to come. The spot price is read by cubic interpolation.
`ThomasAlgorithm` is the allocation-free tridiagonal solver. `ParallelPdeBatchSolver` prices many strikes
with the scalar solver (it is not vectorised, despite the name). Tests compare against a
Richardson-extrapolated reference to 2e-4.

---

## 3. Volatility surfaces

**SVI** (`volatility/SviModel`, Gatheral 2004 raw form): `w(k) = a + b(rho (k - m) + sqrt((k - m)^2 + s^2))`.
Parameter checks guarantee non-negative total variance; they do not by themselves exclude butterfly or
calendar arbitrage.

**SSVI** (`volatility/SsviApproximation`, Gatheral and Jacquier 2014):
`w(k, theta) = theta/2 (1 + rho phi(theta) k + sqrt((phi(theta) k + rho)^2 + 1 - rho^2))` with
`phi(theta) = eta / (theta^gamma (1 + theta)^(1 - gamma))` and k the log-moneyness against the forward. The
class evaluates the paper's sufficient conditions for no butterfly arbitrage in closed form and validates
calendar monotonicity; it reports violations rather than silently "enforcing" anything.

**SABR** (`volatility/SabrModel`, Hagan et al. 2002): `dF = alpha F^beta dW1`, `d alpha = nu alpha dW2`,
`corr = rho`, with the standard asymptotic expansion for Black implied volatility. Known limits of the formula:
it is asymptotic in maturity, inaccurate for long expiries and very low strikes, and can imply negative
density in the wings. `SabrFreeBoundaryModel` is a deprecated alias: the earlier "free-boundary" version was a
clamped Hagan formula, not the Hagan 2014 density correction, and has been removed.

**Local and stochastic-local volatility** (`volatility/SlvApproximation`): Dupire local volatility from an
implied surface, `sigma_loc^2 = (dw/dT) / (1 - k/w dw/dk + 1/4(-1/4 - 1/w + k^2/w^2)(dw/dk)^2 + 1/2 d^2w/dk^2)`
in total-variance form, including the dividend yield. Dupire is only defined for an arbitrage-free surface;
the class detects a negative numerator or denominator and reports it.

---

## 4. Rates

See [DATA.md](DATA.md) section 3: log-linear discount curve, schedule-aware par bootstrap, par-yield
treatment of FRED series, and the single ACT/365F day count.

---

## 5. Risk measures

Historical VaR from a P&L vector, and a 3 x 3 spot/vol stress grid as a margin proxy. Expected shortfall and
Monte Carlo VaR calculators existed and were removed on 2026-10-10 because nothing used them. Definitions and limits are
in [RISK.md](RISK.md) section 4.

---

## 6. Discussed, not implemented

- **Heston pricing**: the Heston SDE `dv = kappa(theta - v)dt + xi sqrt(v) dW` is used only to simulate paths
  for VaR. There is no characteristic-function pricer or calibration.
- **Longstaff-Schwartz Monte Carlo** for Bermudan or path-dependent options.
- **Arbitrage-free SABR** (Hagan 2014 free-boundary density, or a PDE for the SABR density).
- **Full SLV calibration** via the Fokker-Planck equation to recover European prices exactly.
- **Machine-learned surfaces** (neural SVI, physics-informed networks).
