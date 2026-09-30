# High-Fidelity Event-Driven Options Backtest Engine (BACKTEST.md)

This document describes the design and operation of the high-fidelity, event-driven backtesting engine engineered to simulate options strategies with zero look-ahead bias and realistic microstructure modeling.

---

## 1. Engine Design & Architecture

```
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                                Historical Tick Storage (Parquet / HDF5)                   │
│          - Level 3 (L3) Order Book Replay  |  - Full Trade & Quote Messages               │
└─────────────────────────────────────────────┬─────────────────────────────────────────────┘
                                              │
                                              ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                                   Event Loop Dispatcher                                   │
│            (Dispatches Market Data Events & Order Execution Notifications)                │
└──────────────────────────────┬──────────────────────────────┬─────────────────────────────┘
                               │                              │
                               ▼                              ▼
┌─────────────────────────────────────────────┐  ┌──────────────────────────────────────────┐
│             Strategy Logic                  │  │       Microstructure Matching Engine     │
│   - Signal Generation                       │  │   - Queue Position Simulator             │
│   - Portfolio Greeks Calculation            │  │   - Exchange Fee & Margin Engine         │
│   - Dynamic Delta Hedging                   │  │   - Market Impact & Slippage Models      │
└─────────────────────────────────────────────┘  └──────────────────────────────────────────┘
```

---

## 2. Realistic Microstructure & Execution Simulation

Options backtesting produces wildly inaccurate results if simple mid-price fill assumptions are used. The engine implements three microstructural realism modules:

### 2.1 Order Book Queue Position Simulator
When posting passive limit orders, the engine models queue priority:
- Track trade volume executed at the limit price.
- Estimate initial queue depth $Q_0$ upon order entry.
- Fill order only after $Q_0$ shares/contracts at or better than the price have traded.

### 2.2 Market Impact Model (Almgren-Chriss)
For aggressive sweep orders, execution prices incorporate transient and permanent market impact:

$$\Delta S_{\text{impact}} = \gamma \cdot \text{Sign}(\text{Order}) \cdot \left( \frac{\text{Volume}}{\text{ADV}} \right)^\alpha \cdot \sigma_{\text{daily}}$$

### 2.3 Exchange Fee Schedules & Clearing Costs
All transactions automatically apply accurate venue fee structures:

| Venue | Contract Type | Exchange Fee / Contract | Clearing Fee (Eurex / LCH) |
| :--- | :--- | :--- | :--- |
| **Eurex** | Index Options (FSTX) | €0.20 | €0.05 |
| **Eurex** | Single Stock Options | €0.15 | €0.03 |
| **Euronext** | Stock Options (AEX/CAC) | €0.18 | €0.04 |
| **LSEG** | FTSE 100 Options | £0.16 | £0.04 |

---

## 3. Options-Specific Backtest Features

- **Dynamic Volatility Re-calibration**: Volatility surface fits are updated strictly at historical tick timestamps without utilizing future ticks.
- **Path-Dependent Delta Hedging**: Simulates intraday dynamic delta hedging triggered by spot price movements ($\Delta \ge 2\%$) or fixed time intervals (e.g., hourly / end-of-day).
- **Pin Risk & Expiry Settlement**: Accurately simulates physical delivery vs. cash settlement on expiration dates, including pin risk for options expiring near-the-money.
- **Corporate Action Adjustments**: Adjusts historical strikes, contract multipliers, and underlying stock prices seamlessly across historical ex-dates.

---

## 4. Backtest Metrics & Performance Analytics

Reports generated after each simulation run include:

- **Sharpe & Sortino Ratio** (annualized against ESTER benchmark rate).
- **Maximum Drawdown (MDD)** and Drawdown Duration.
- **Greek Drift Statistics**: Portfolio Delta, Gamma, Vega, and Theta exposure over time.
- **Turnover & Capacity Analysis**: Estimates maximum strategy AUM before market impact degrades alpha.
