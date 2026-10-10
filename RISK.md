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
| 3 | Portfolio admission | `PortfolioRiskAdmission` | the post-trade book would exceed `risk.max_notional`, `risk.max_delta`, `risk.max_gamma`, `risk.max_vega` or `risk.max_position` (absolute net quantity), using the position's own multiplier or the order's for a new symbol. While any open option position has not been valued by the pricer, the book's Greeks are incomplete, so an order that does not reduce a position is refused (closing what is held is always allowed); a fill is valued at once, not at the next scheduled mark |
| 4 | Pre-trade filter | `PreTradeRiskFilter` | quantity <= 0, non-finite or non-positive price, quantity above the per-order maximum, notional (qty x price x multiplier) above the per-order maximum, projected per-underlying exposure above its `ConcentrationLimitManager` limit, the market is too wide or thin (`LiquidityRiskMonitor`: spread in bps and minimum volume), or the per-second order rate is exceeded |
| 5 | Transport | `PaperTradingExecutionAdapter` or `AlpacaPaperTransport` | never, in the simulator; Alpaca rejects on its own rules (options level, buying power, closed market) and the transport rejects when nothing fills within `execution.fill_wait_seconds` (the remainder is cancelled first) |
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

`core/PortfolioValuationService` marks the book every five seconds: an option at its quote mid (or the
Black-Scholes price when there is no two-sided quote) with Greeks from the fitted SVI slice at its strike and
expiry (falling back to the quote's implied volatility, then the feed's figure), a stock at spot with delta
one, an expired contract at intrinsic and flagged. It writes each option's delta, gamma and vega into its
position, so the execution book's Greeks and the risk panel are pricer-derived, not linear placeholders, and
keeps unrealised P&L against the tracker's average cost and realised P&L from reductions. Every number carries
its source (`MID`/`MODEL`/`SPOT`/`INTRINSIC`, `SVI`/`MARKET_IV`/`FEED_IV`) and a contract that cannot be
valued is reported as such, not as zero. `/api/valuation` returns it; before the first spot it says why not.

`execution/PnlHistory` samples the valuation to `pnl_history.csv` under `DATA_DIR`, once a minute and at once
when realised P&L changes. Realised and unrealised P&L are cumulative from a flat book, because the tracker is
rebuilt from the fill ledger at start, so their sum is the P&L since the ledger began. The trading day is the
calendar day in New York. Today's P&L is measured from the last sample before the day started (yesterday's
last mark), or from the first sample of the day when there is none; the day's maximum drawdown is the largest
fall from a running peak of total P&L over that series. `/api/pnl` returns the figures with the day's series
and the risk panel shows them.

---

## 3. Alerts and the trading halt

`risk/GreekAlertManager` compares aggregate delta, gamma and vega with a WARNING threshold (80% of the
configured `risk.max_delta`, `risk.max_gamma`, `risk.max_vega`) and a CRITICAL one (the limit itself), the
same limits the portfolio admission gate enforces. The aggregates are the pricer-derived Greeks the valuation
service writes into each position, so an options book that breaches a limit halts trading. State changes fire listeners; a metric de-escalates only after falling below a fraction of its threshold
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
