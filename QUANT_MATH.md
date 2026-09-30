# Quantitative Math & Model Specifications

This document outlines the advanced mathematical models implemented in the SBK Options Pricing Engine.

## 1. Discrete Dividend Modeling
In reality, equities do not pay continuous yields ($e^{-qT}$). They pay discrete dividends at specific times.
To prevent arbitrage in American option pricing, our PDE (Partial Differential Equation) grids implement discrete jump conditions.

### Crank-Nicolson Dividend Jump
At the exact moment of an ex-dividend date $t_{ex}$, the stock price drops by the dividend amount $D$.
The option value boundary condition is shifted:
$$ V(S, t_{ex}^-) = V(S - D, t_{ex}^+) $$
This spatial interpolation is handled using cubic splines across the PDE grid to preserve the Greeks (Delta/Gamma) smoothly.

## 2. Stochastic Local Volatility (SLV)
While SABR is excellent for single-expiry smiles, and SVI guarantees smooth interpolation, neither prevents cross-expiry (calendar) arbitrage natively.

To achieve a strictly arbitrage-free surface, we utilize SLV.
The SLV model assumes the stock price follows:
$$ dS_t = \mu S_t dt + \sigma_{local}(t, S_t) \sqrt{v_t} S_t dW_t^{(1)} $$
Where $\sigma_{local}$ is a non-parametric local volatility function calibrated via the Fokker-Planck (Kolmogorov Forward) equation to exactly recover European market prices. This ensures the joint density function remains strictly positive, eliminating butterfly arbitrage.
