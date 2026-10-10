# Backtesting (BACKTEST.md)

What the backtest code does today, with its simplifications stated, and what a serious options backtester
would still need.

---

## 1. What exists

| Class | Role |
| :--- | :--- |
| `risk/PortfolioBacktestOrchestrator` | runs `StrategyExecutionLoop` over a list of `OptionSnapshot` mid prices through a real `OrderManager` with the paper adapter and an in-memory `PositionTracker` (`FillRecorder.NONE`, so a backtest can never write to the live fill ledger); reports snapshots processed, signals, accepted and rejected orders, net quantity, total P&L, average slippage and maximum drawdown (the largest fall of cumulative P&L from its running peak, never negative; a position still open at the end is marked at the last price and counts as the one trade it was entered as) |
| `risk/HistoricalReplayBacktester` | turns stored snapshots into the trade-signal sequence and P&L metrics used above |
| `data/HistoricalDataManager` | append-only in-memory snapshot store; the surface service stores four quotes of each chain it loads into it at every calibration, not on the per-minute quote reload |

`Main` runs the orchestrator once at startup over a four-snapshot demo series and prints the summary line.

---

## 2. Simplifications you must know about

- **Fills**: every accepted order fills in full at the quoted side moved by `execution.slippage_bps`. No
  partial fills, no queue, no impact (the Almgren-Chriss function exists but is not wired in).
- **Prices**: the strategy trades the snapshot *mid price* as if it were the underlying spot. Option
  snapshots are not priced through a model during the backtest.
- **Mark-to-market**: P&L is computed from execution prices only; there is no end-of-period revaluation of
  open positions through a pricer.
- **No fees**, no clearing costs, no borrow, no financing.
- **No expiry handling**: options never expire or settle inside a backtest.
- **No look-ahead protection beyond ordering**: snapshots are sorted by timestamp; nothing else is enforced.
- **Data**: `market_data.csv` is a synthetic chain around a real spot. Backtests on it test the machinery,
  not a strategy.

---

## 3. What a real options backtester needs (not implemented)

- Historical option chains with timestamps (storage, e.g. Parquet; a loader; survivorship-safe symbol master).
- Pricing and Greeks of every open position at each step, with the volatility surface re-fitted only from
  data at or before that time.
- Expiry settlement (cash or physical), pin risk near the money, assignment for American options.
- Corporate actions: strike and multiplier adjustments across ex-dates and splits.
- Execution realism: queue position, partial fills, Almgren-Chriss impact, venue fee schedules.
- Metrics: Sharpe/Sortino against a cash benchmark, drawdown duration, turnover, capacity.
