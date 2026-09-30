# Market Data Infrastructure & Volatility Surface Pipeline (DATA.md)

This document specifies the market data ingestion pipeline, real-time tick processing architecture, yield curve bootstrapping, and volatility surface calibration models supporting European derivative venues.

---

## 1. Exchange Market Data Integration

The platform consumes raw multicast binary market data directly from co-located exchange network feeds:

```
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                                Exchange Network Multicast Feeds                           │
│  - Deutsche Börse: Eurex EOBI (Enhanced Order Book Interface)                             │
│  - Euronext: Optiq MDG (Market Data Gateway - FAST/FIX)                                   │
│  - LSEG: GTP (Group Ticker Plant)                                                         │
│  - SIX Swiss: QDF (Quick Data Feed)                                                       │
└─────────────────────────────────────────────┬─────────────────────────────────────────────┘
                                              │ Solarflare EF_VI / DPDK
                                              ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                               Direct NIC Off-Heap Ring Buffer                             │
└─────────────────────────────────────────────┬─────────────────────────────────────────────┘
                                              │
                                              ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                        L3 Order Book Reconstruction & Normalizer                          │
│     (Decodes ITCH/EOBI messages -> updates zero-copy off-heap limit order book)           │
└─────────────────────────────────────────────┬─────────────────────────────────────────────┘
                                              │
                                              ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                       Real-Time Volatility Surface Calibration                             │
│          (Continuous non-linear least squares fit for SABR & SSVI parameters)             │
└───────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Volatility Surface Calibration Engine

To ensure price consistency and prevent arbitrage across strikes and expirations, the engine continuously fits parametric models to option chain bid/ask mid-prices.

### 2.1 SSVI (Surface Stochastic Volatility Inspired)
The SSVI model guarantees absence of static calendar and butterfly arbitrage across the entire volatility surface:

$$w(k, \theta_t) = \frac{\theta_t}{2} \left( 1 + \rho \phi(\theta_t) k + \sqrt{(\phi(\theta_t) k + \rho)^2 + (1 - \rho^2)} \right)$$

where:
- $k = \ln(K / F_T)$ is the log-moneyness.
- $\theta_t$ is the total ATM variance for maturity $T$.
- $\rho \in (-1, 1)$ governs the skew angle.
- $\phi(\theta_t) = \frac{\eta}{\theta_t^\gamma (1 + \theta_t)^{1-\gamma}}$ controls smooth smile curvature.

### 2.2 SABR Model Calibration
For individual option maturities, the SABR model fits forward volatility dynamics:

$$\sigma_{\text{SABR}}(F, K, T, \alpha, \beta, \rho, \nu)$$

- $\alpha$: Initial volatility.
- $\beta$: CEV exponent (fixed at 0.5 for equities/indexes or 1.0 for log-normal).
- $\rho$: Correlation between asset price and volatility.
- $\nu$: Volatility of volatility (vol-of-vol).

**Fitting Objective**:
$$\min_{\alpha, \rho, \nu} \sum_{i=1}^M w_i \left( \sigma_{\text{market}}^{(i)} - \sigma_{\text{SABR}}(K_i, \alpha, \rho, \nu) \right)^2$$

---

## 3. Interest Rate Curves & Yield Bootstrapping

European options require precise multi-currency discount curves:

| Currency | Rate Benchmark | Curve Construction Method |
| :--- | :--- | :--- |
| **EUR** | ESTER (€STR) | OIS Bootstrapping with Monotone Convex Interpolation |
| **GBP** | SONIA | OIS Bootstrapping with Cubic Spline Interpolation |
| **CHF** | SARON | Swiss Money Market Swap Curve Bootstrapping |
| **SEK / NOK** | STIBOR / NIBOR | Deposit & FRA/Futures Swap Curve |

---

## 4. Corporate Actions & Discrete Dividend Pipeline

Unlike index options which assume continuous dividend yield ($q$), single-stock European options require discrete dividend adjustment:

- **Dividend Schedule Ingestion**: Automated ingestion of verified corporate action announcements via market data vendor APIs (Refinitiv / Bloomberg / Exchange Notices).
- **Forward Price Adjustment**:
  $$F_T = \left( S_0 - \sum_{i=1}^n D_i e^{-r t_i} \right) e^{r T}$$
  where $D_i$ is the discrete dividend paid at time $t_i \le T$.
- **Ex-Date Stock Adjustments**: Automatic adjustment of historical tick databases during stock splits, reverse splits, spin-offs, and rights issues.
