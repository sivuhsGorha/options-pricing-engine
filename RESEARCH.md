# Academic Foundations & Quantitative Research (RESEARCH.md)

This document details the quantitative models, stochastic partial differential equations (PDEs), numerical methods, and academic research foundations underlying the engine.

---

## 1. Stochastic Volatility Models

While Black-Scholes-Merton assumes constant volatility $\sigma$, real options markets exhibit strong volatility smiles and skews. The platform implements three advanced stochastic volatility models for pricing and risk:

### 1.1 The Heston Stochastic Volatility Model
Asset price $S_t$ and variance $v_t$ follow coupled stochastic differential equations (SDEs):

$$dS_t = (r - q) S_t dt + \sqrt{v_t} S_t dW_t^S$$

$$dv_t = \kappa (\theta - v_t) dt + \xi \sqrt{v_t} dW_t^v$$

$$\text{Corr}(dW_t^S, dW_t^v) = \rho dt$$

where:
- $\kappa$: Rate of mean reversion.
- $\theta$: Long-term variance mean.
- $\xi$: Volatility of volatility (vol-of-vol).
- $\rho$: Correlation between asset returns and volatility shocks (captures leverage effect/skew).

**Analytical Solution**: Solved via characteristic function integration using Gauss-Legendre quadrature.

---

### 1.2 The SABR Model (Hagan et al. 2002)
Used extensively for calibrating option smile dynamics across individual maturities:

$$dF_t = \alpha_t F_t^\beta dW_t^1$$

$$d\alpha_t = \nu \alpha_t dW_t^2$$

$$\text{Corr}(dW_t^1, dW_t^2) = \rho dt$$

---

## 2. American Option Pricing & Numerical PDE Solvers

American options allow exercise at any time $t \le T$. Because no closed-form analytical solution exists, two high-performance numerical schemes are implemented:

### 2.1 Trinomial Tree Framework
Constructs a discrete-time recombination tree matching the first two moments of log-asset returns. At each tree node $(i, j)$:

$$V_{i,j} = \max\left( \text{IntrinsicValue}_{i,j}, \; e^{-r \Delta t} \left( p_u V_{i+1, j+1} + p_m V_{i+1, j} + p_d V_{i+1, j-1} \right) \right)$$

### 2.2 Crank-Nicolson Finite Difference PDE Solver
Solves the Black-Scholes PDE subject to early exercise boundary conditions:

$$\frac{\partial V}{\partial t} + \frac{1}{2} \sigma^2 S^2 \frac{\partial^2 V}{\partial S^2} + (r - q) S \frac{\partial V}{\partial S} - r V = 0$$

Using the implicit/explicit Crank-Nicolson scheme results in a tridiagonal matrix equation solved via the Thomas Algorithm in $\mathcal{O}(N)$ time per step.

---

## 3. Longstaff-Schwartz Monte Carlo (LSMC)

For high-dimensional path-dependent options (e.g. Bermudan basket options), early exercise boundaries are estimated by cross-sectional regression:

$$\mathbb{E}[V_{t+1} \mid S_t] \approx \sum_{k=0}^M a_k L_k(S_t)$$

where $L_k(S_t)$ are orthogonal Laguerre polynomials fitted via least-squares across simulated paths at each exercise date.

---

## 4. Machine Learning Implied Volatility Surface Prediction

Research branch exploring neural network approximations:
- **Neural SVI**: Deep neural networks trained on historical tick surfaces to predict intraday SSVI parameter shifts $(\theta, \rho, \eta, \gamma)$ $500 \text{ ms}$ ahead.
- **Physics-Informed Neural Networks (PINNs)**: Neural network pricing models enforced with strict Black-Scholes PDE loss penalties to guarantee arbitrage-free outputs.
