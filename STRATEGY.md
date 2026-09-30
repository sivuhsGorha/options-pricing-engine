# Quantitative Options Strategies & Alpha Generation (STRATEGY.md)

This document details the quantitative options trading strategies, alpha models, and arbitrage mechanics implemented within the engine for European exchange venues (Eurex, Euronext, LSEG, SIX, Nasdaq Nordic).

---

## 1. Core Trading Strategies

### 1.1 Volatility Arbitrage & Skew Trading
Volatility arbitrage exploits mispricings between the market implied volatility surface and predicted real/realized volatility derived from high-frequency underlying asset dynamics.

- **Smile/Skew Arbitrage**: Trade misalignments between OTM Put/Call implied volatilities vs. smooth parametric models (SABR / SSVI).
- **Term Structure Arbitrage**: Capitalize on mispricings across expiration dates (calendar spreads) when the implied volatility term structure deviates from mean-reverting regime dynamics.
- **Delta-Neutral Execution**: Every volatility trade is continuously hedged against underlying spot movements to isolate pure volatility expansion/contraction ($\text{Vega}$) and decay ($\Theta$).

### 1.2 Automated Delta-Neutral Market Making
High-frequency automated market making across index options (e.g., EURO STOXX 50 - FSTX, DAX 40 - FDAX, FTSE 100) and liquid single-stock options on Euronext & Deutsche Börse.

- **Dynamic Quote Width**: Quotes adapt dynamically to toxic order flow, order book imbalance, and real-time realized volatility:
  $$\text{Half-Spread} = \frac{\gamma}{2} \sigma^2 (T - t) + \frac{1}{\gamma} \ln\left(1 + \frac{\gamma}{\kappa}\right)$$
- **Inventory Skewing**: Adjust bid/ask quotes based on current net delta and gamma inventory positions to naturally attract balancing order flow.
- **Microsecond Hedging**: Auto-execute underlying futures or equity trades when net portfolio delta breaches specified risk bands ($\Delta_{\text{net}} > \Delta_{\text{threshold}}$).

### 1.3 Index Dispersion & Correlation Arbitrage
Capitalizes on the structural mispricing between index option implied volatility and individual constituent option implied volatilities.

- **Index vs. Constituent Dynamics**:
  $$\sigma_{\text{index}}^2 = \sum_{i=1}^N w_i^2 \sigma_i^2 + 2 \sum_{i<j} w_i w_j \sigma_i \sigma_j \rho_{ij}$$
- **Trade Structure**: Sell over-priced index options (e.g., EURO STOXX 50 options on Eurex) while buying a weighted basket of constituent single-stock options (e.g., ASML, LVMH, SAP, TotalEnergies) when implied correlation ($\rho_{\text{implied}}$) significantly exceeds historical/predicted correlation.

### 1.4 Dividend & Ex-Date Arbitrage
European options feature discrete dividend drops. Miscalculations of dividend amounts or timing create risk-free or low-risk arbitrage opportunities around ex-dividend dates.

- **Synthetic Forward Arbitrage**: Exploits violations in Put-Call parity near ex-dividend dates:
  $$\text{Implied Dividend } D = S - K e^{-rT} - (C - P) e^{rT}$$
- **Early Exercise Arbitrage (American Style)**: Solves optimal exercise timing on deep ITM call options prior to large dividend drops.

---

## 2. Signal Generation & Alpha Pipeline

```
┌───────────────────────────┐     ┌───────────────────────────┐     ┌───────────────────────────┐
│ Real-Time Order Book Feed │ ──> │ Order Flow Imbalance (OFI)│ ──> │ Short-Term Vol Trend      │
└───────────────────────────┘     └───────────────────────────┘     └─────────────┬─────────────┘
                                                                                  │
┌───────────────────────────┐     ┌───────────────────────────┐                   │
│ Real-Time Vol Surface     │ ──> │ Surface Mispricing Delta  │ ──────────────────┤
└───────────────────────────┘     └───────────────────────────┘                   │
                                                                                  ▼
┌───────────────────────────┐                                       ┌───────────────────────────┐
│ High-Frequency Tick Data  │ ────────────────────────────────────> │ Multi-Factor Alpha Signal │
└───────────────────────────┘                                       └─────────────┬─────────────┘
                                                                                  │
                                                                                  ▼
                                                                    ┌───────────────────────────┐
                                                                    │ Order Generator & Router  │
                                                                    └───────────────────────────┘
```

### Alpha Factors:
1. **Order Flow Imbalance (OFI)**: Microsecond order book pressure on options quote levels.
2. **Implied Volatility Velocity ($\frac{\partial \sigma_{\text{imp}}}{\partial t}$)**: Rapid shifts in volatility skew indicating institutional positioning.
3. **Realized-to-Implied Volatility Spread (RIVS)**: Difference between 5-minute HAR-RV (Heterogeneous Autoregressive Realized Volatility) and front-month implied vol.

---

## 3. European Market Specifics & Microstructure

- **Trading Hours & Auctions**: Engine handles morning opening auctions (08:50 - 09:00 CET), intra-day volatility halts, and closing auctions (17:30 CET).
- **Continuous vs. Discrete Dividends**: Full continuous dividend yield support ($q$) for index options alongside discrete dividend matrices for individual European stocks.
- **Negative & Low Interest Rate Regimes**: Full precision modeling under ESTER (Euro Short-Term Rate), SONIA (UK), and SARON (Switzerland) yield curves.
