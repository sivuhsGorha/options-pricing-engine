# Risk Controls (RISK.md)

What stops a bad order, what measures the book, and what each number means. Everything in sections 1-5 is
implemented and tested. Section 6 lists what is not.

---

## 1. The order path and its gates

Every order submitted to `execution/OrderManager.submit` passes these checks in this order. The first failure
rejects the order with the quoted reason, which the dashboard shows in the paper-trading panel.

| # | Gate | Where | Rejects when |
| :-- | :--- | :--- | :--- |
| 1 | Trading halt | `TradingHalt` | the halt is tripped ("trading halted: <reason>") |
| 2 | Market-data policy | `OrderManager.MarketDataPolicy` | the quote's status is not tradable, or the quote is older than 30 s ("market data not tradable" / "too old"). STALE and UNAVAILABLE can never be made tradable; SIMULATED only with `ALLOW_SIMULATED_DATA=true` |
| 3 | Portfolio admission | `PortfolioRiskAdmission` | the post-trade book would exceed `risk.max_notional`, `risk.max_delta`, `risk.max_gamma`, `risk.max_vega` or `risk.max_position` (absolute net quantity), using the position's own multiplier or the order's for a new symbol |
| 4 | Pre-trade filter | `PreTradeRiskFilter` | quantity <= 0, non-finite or non-positive price, quantity above the per-order maximum, notional (qty x price x multiplier) above the per-order maximum, projected per-underlying exposure above its `ConcentrationLimitManager` limit, the market is too wide or thin (`LiquidityRiskMonitor`: spread in bps and minimum volume), or the per-second order rate is exceeded |
| 5 | Transport | `PaperTradingExecutionAdapter` | never, in paper trading; a real transport could |
| 6 | Fill validation | `OrderManager` | the transport reports a fill quantity that is negative or larger than requested. This trips the halt: it indicates a broken venue link |
| 7 | Booking | `PositionTracker.applyFill` | the fill cannot be recorded. Also trips the halt ("order executed but booking failed") because the book no longer matches reality |

Accepted orders are booked with the contract multiplier, update the concentration exposure and are appended to a
bounded audit trail (`/api/execution`).

---

## 2. Greeks tracked

Per position (`risk/PortfolioPosition`) and aggregated over the book (`risk/GreekAggregator`,
`execution/PositionTracker.snapshotExposure`):

- First order: delta, vega; theta and rho are computed by `Greeks` for pricing but not aggregated.
- Second order: gamma, vanna, volga, charm.
- Third order: speed, color.

`risk/greeks/AnalyticalHigherGreeks` gives the closed-form higher-order values; `GreekDriftCalculator` projects
delta decay (charm) and gamma bleed (color) over a horizon.

The position tracker's default Greek model is **linear delta** (delta = quantity x multiplier) until a
pricing model updates a position's Greeks; this is the number the dashboard shows as "net delta" from the
execution book.

---

## 3. Alerts and the trading halt

`risk/GreekAlertManager` compares aggregate delta, gamma and vega with a WARNING and a CRITICAL threshold
each. State changes fire listeners; a metric de-escalates only after falling below a fraction of its threshold
(hysteresis), so a value oscillating around a limit does not spam alerts. `execution/TradingHalt` is wired to
the manager at startup: any CRITICAL alert trips the halt, after which every order is rejected until an
operator calls `resume()`. The halt state and reason are exposed on `/api/positions` and shown in the dashboard.

---

## 4. Risk measures

| Measure | Class | What it computes |
| :--- | :--- | :--- |
| Historical VaR | `HistoricalVaRCalculator` | the loss at a confidence level from a vector of historical daily P&L (minimum 10 samples), scaled by `sqrt(holding days)`; a non-positive tail loss reports 0 |
| Expected shortfall | `ExpectedShortfallCalculator` | the mean loss beyond the VaR quantile of the same P&L vector |
| Monte Carlo VaR | `MonteCarloVaRCalculator` | P&L quantile from simulated spot paths under Heston dynamics (`SlvParams`), given delta/gamma/vega |
| Margin approximation | `MarginApproximation` | worst loss over four corners (spot +/- shock, vol +/- shock) of a delta-gamma-vega Taylor expansion. **This is not Eurex Prisma or SPAN**; it is a first-order stress proxy and is labelled "scenario margin" in the UI |
| Margin optimizer | `MarginOptimizer` | the delta hedge quantity that minimises the margin approximation above, and the resulting reduction |
| Liquidity | `LiquidityRiskMonitor` | spread in basis points and a minimum volume |

The engine publishes net delta, gamma, vega and the scenario margin to the memory-mapped state file every
tick; the dashboard reads them from there (`/api/risk`) and overlays the execution book's own exposure.

---

## 5. Configuration

```yaml
risk:
  max_notional: 1000000.0    # portfolio admission and per-order notional
  max_delta: 5000.0
  max_gamma: 1000.0
  max_vega: 10000.0
  max_position: 10000.0      # absolute net quantity per symbol; also the per-order quantity cap
  max_concentration: 1000000.0   # per-underlying exposure, defaults to max_notional
```

Limits must be positive; the composition root rejects a configuration that is not. The pre-trade filter's
order-rate limit is 100 orders per second.

---

## 6. Not implemented

- Automatic delta hedging or any order generated by a risk breach. A breach halts; it does not trade.
- Exchange-level kill switches, cancel-on-disconnect, mass cancel. There is no exchange session to cancel.
- Clearing-house margin models (Eurex Prisma, SPAN). The margin shown is the approximation in section 4.
- A standing stress-scenario matrix. The four-corner stress in the margin approximation is the only scenario set.
- Theta and rho aggregation across the book.
