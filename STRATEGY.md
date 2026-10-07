# Strategy (STRATEGY.md)

This document describes the one strategy that is implemented and how its signals become paper orders.
Ideas that are not implemented are listed separately at the end so nobody mistakes them for features.

---

## 1. The implemented strategy: threshold momentum

`execution/StrategyExecutionLoop` is the only strategy in the codebase. It is deliberately simple; its job is
to exercise the order path end to end, not to make money.

- **Input**: the latest spot price for one symbol (`execution.symbol`, default `SPY`), fed every engine tick
  (10 ms) from `core/QuantSimulationHarness`.
- **Signal**: the relative move against the *previous price the loop saw*,
  `|p_t - p_{t-1}| / max(|p_{t-1}|, 1)`. If it is at least `strategy.trigger_pct` (default `0.001`, i.e. 0.1%),
  one signal fires: buy if the price rose, sell if it fell. A single jump that then holds produces one signal,
  not one per tick.
- **Size**: `strategy.base_quantity` (default 10) contracts, scaled by `execution.contract_multiplier`
  (default 1) everywhere notional, exposure and positions are computed.
- **Order**: a limit order at the current price, submitted to `OrderManager` with the market snapshot that
  produced the price. The order can be rejected at any of the stages described in [EXECUTION.md](EXECUTION.md)
  and [RISK.md](RISK.md). Rejections are counted and shown in the dashboard's paper-trading panel with the reason.

### Configuration

```yaml
execution:
  symbol: SPY
  slippage_bps: 25          # paper fills move this far against you
  contract_multiplier: 1    # 100 for standard equity options
strategy:
  base_quantity: 10
  trigger_pct: 0.001        # 0.00001 is useful for exercising the order path in testing
```

### What the strategy does not do
- No hedging, no position targeting, no stop-loss, no time-of-day logic.
- No awareness of option prices: it trades the underlying's spot series through the paper adapter.
- It will not trade on STALE or UNAVAILABLE market data (see the market-data policy in EXECUTION.md).

---

## 2. Backtesting the strategy

`risk/PortfolioBacktestOrchestrator` runs the same loop over a list of `OptionSnapshot` mid prices and reports
signals, accepted and rejected orders, net quantity, P&L, average slippage and maximum drawdown. `Main` runs it
once at startup over a four-snapshot demo series. See [BACKTEST.md](BACKTEST.md) for the limits of that engine.

---

## 3. Research backlog (not implemented)

These are candidate strategies, kept here as notes. None has code, tests or data behind it.

- **Volatility arbitrage / skew trading**: trade the gap between market implied volatility and a fitted
  parametric surface (the SSVI and SABR models in `volatility/` would be the fitted side). Needs a real option
  chain feed with timestamps and a calibration step; the current dashboard surface uses fixed demo parameters.
- **Delta-neutral market making** with an Avellaneda-Stoikov style quote width
  `half-spread = (gamma/2) sigma^2 (T - t) + (1/gamma) ln(1 + gamma/kappa)` and inventory skew. Needs quoting
  and cancel support in a transport; the paper adapter fills every order in full.
- **Dispersion / correlation arbitrage** between index options and constituent options:
  `sigma_index^2 = sum w_i^2 sigma_i^2 + 2 sum_{i<j} w_i w_j sigma_i sigma_j rho_ij`. Needs multi-symbol chains.
- **Dividend / ex-date arbitrage** using implied dividends from put-call parity,
  `D = S - K e^{-rT} - (C - P)`; the PDE pricer already handles discrete cash dividends, the data feed does not
  supply reliable dividend schedules (see [DATA.md](DATA.md)).
