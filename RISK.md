# Institutional Risk Management & Real-Time Controls (RISK.md)

This document establishes the real-time risk management architecture, Greek exposure limits, Value-at-Risk (VaR) calculations, initial margin modeling (Eurex Prisma / SPAN), and automated emergency circuit breakers.

---

## 1. Multi-Tier Greek Sensitivity Risk Matrix

The platform tracks and enforces hard exposure limits across first-, second-, and third-order option Greeks in real time across all portfolios and strategies:

```
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                                    Real-Time Risk Aggregator                              │
└──────────────┬──────────────────────────────────┬──────────────────────────┬──────────────┘
               │                                  │                          │
               ▼                                  ▼                          ▼
┌────────────────────────────┐  ┌───────────────────────────┐  ┌────────────────────────────┐
│   First-Order Sensitivities│  │ Second-Order Sensitivities│  │  Third-Order Sensitivities │
│  - Delta (Spot Sensitivity)│  │  - Gamma (Delta Speed)    │  │   - Speed (d3V / dS3)      │
│  - Vega (Vol Sensitivity)  │  │  - Vanna (dDelta / dVol)  │  │   - Color (dGamma / dt)    │
│  - Theta (Time Decay)      │  │  - Volga (Vol Sensitivity)│  │   - Ultima (dVolga / dVol) │
│  - Rho (Rate Sensitivity)  │  │  - Charm (Delta Decay)    │  │                            │
└────────────────────────────┘  └───────────────────────────┘  └────────────────────────────┘
```

### Risk Limits Table

| Greek Parameter | Definition | Hard Portfolio Max Limit | Breach Action |
| :--- | :--- | :--- | :--- |
| **Net Portfolio Delta ($\Delta_{\text{net}}$)** | Cash equivalent spot exposure | $\pm €5,000,000$ | Trigger Auto-Hedge in Futures |
| **Portfolio Gamma ($\Gamma_{\text{net}}$)** | Rate of change of Delta per €1 spot move | $\pm €250,000 / \text{point}$ | Halt New Positions |
| **Portfolio Vega ($V_{\text{net}}$)** | P&L impact per 1% move in implied vol | $\pm €100,000 / \text{vol pt}$ | Block Volatility Buying/Selling |
| **Vanna ($\frac{\partial \Delta}{\partial \sigma}$)** | Delta shift per 1% move in volatility | $\pm €50,000$ | Risk Warning Alert |
| **Volga ($\frac{\partial^2 V}{\partial \sigma^2}$)** | Vega shift per 1% move in volatility | $\pm €25,000$ | Risk Warning Alert |

---

## 2. Value at Risk (VaR) & Expected Shortfall (ES)

Risk computations execute asynchronously every 100 milliseconds across three distinct models:

1. **Parametric Delta-Gamma VaR**: Uses analytical covariance matrices of spot returns and volatility shifts to estimate 99% 1-day VaR.
2. **Historical Simulation VaR (5000 Ticks)**: Replays past 5000 stress ticks onto current portfolio positions to capture fat-tail non-linear distributions.
3. **Monte Carlo Expected Shortfall (Tail Risk)**: Simulates 100,000 joint spot-volatility paths under a Student-$t$ copula to compute expected losses beyond the 99th percentile:

$$\text{ES}_{\alpha} = \frac{1}{1 - \alpha} \int_{\alpha}^1 \text{VaR}_u \, du$$

---

## 3. Stress Testing & Scenario Analysis

The system subjects the active portfolio to matrix stress tests continuously:

| Scenario Name | Spot Move ($\Delta S$) | Volatility Shock ($\Delta \sigma$) | Interest Rate Shock |
| :--- | :--- | :--- | :--- |
| **Market Crash (Black Monday)** | $-30\%$ | $+100\%$ (Vol spike) | $-50 \text{ bps}$ |
| **Vol Collapse (Rally)** | $+15\%$ | $-40\%$ (Vol crush) | $+25 \text{ bps}$ |
| **Severe Liquidity Freeze** | $-15\%$ | $+50\%$ | Spread widens 300% |
| **Ex-Dividend Shock** | $-5\%$ | $0\%$ | Unexpected 100% div cut |

---

## 4. Initial Margin Requirements (Eurex Prisma / SPAN)

To prevent margin call breaches and optimize capital efficiency, the engine embeds a full replication of clearinghouse margin engines:

- **Eurex Prisma**: Calculates portfolio-based initial margin using filtered historical simulation across joint equity/index/derivatives portfolios.
- **SPAN (Standard Portfolio Analysis of Risk)**: Computes margin requirements for Euronext & LSEG derivatives across 16 scenario grids.

---

## 5. Automated Hardware & Software Kill Switches

```
                     ┌─────────────────────────────────────────┐
                     │     Real-Time Exposure & PnL Monitor    │
                     └────────────────────┬────────────────────┘
                                          │
                   ┌──────────────────────┴──────────────────────┐
                   │  Is Net Drawdown > 3.5% OR Delta > Limit?   │
                   └──────────────────────┬──────────────────────┘
                                          │
                                YES ──────┴────── NO ──> [Continue Trading]
                                │
                                ▼
         ┌──────────────────────────────────────────────────────────────┐
         │                    ACTIVATING KILL SWITCH                    │
         ├──────────────────────────────────────────────────────────────┤
         │ 1. Send FIX Session Cancel-On-Disconnect (COD) to Exchange    │
         │ 2. Issue Bulk Mass Cancel for all resting active quotes       │
         │ 3. Execute Market Orders to flatten Net Portfolio Delta       │
         │ 4. Lock Order Entry Gateway (Hardware UDP Socket Shutdown)   │
         └──────────────────────────────────────────────────────────────┘
```
