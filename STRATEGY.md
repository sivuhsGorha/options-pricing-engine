# Strategy (STRATEGY.md)

This document describes the two strategies that are implemented, how their signals become paper orders, and
what each one does not do. Ideas that are not implemented are listed separately at the end so nobody mistakes
them for features. `strategy.mode` selects which one runs; the operator switch stops either.

---

## 1. Vol spread (default): the front-month straddle against the fitted surface

`execution/VolSpreadStrategy`, stepped by the harness every `strategy.option_interval_seconds` (30 s).

- **Signal**: the market implied volatility of the front-month at-the-money straddle (the call and put mids
  inverted and averaged; the front month is the nearest loaded expiry at least `min_days_to_expiry` out, the
  strike the one nearest the forward) against the fitted **reference surface** at the same strike and expiry.
  The reference is the global SSVI fit (`strategy.reference_model`, `SSVI` or `SVI`): a per-slice SVI fit sits on
  the quotes it was fitted to and carries no signal about them, whereas the three-parameter global surface is a
  smoothed value the market deviates from. `edge = market IV - reference IV`.
- **Entry**: edge above `vol_edge` (default 1 vol point) sells `contracts` straddles; below `-vol_edge` buys.
  One straddle at a time. The second leg is never sent if the first is refused by the order gates. If the first
  leg fills and the second is refused, the first is closed in the same step; if that close is refused too, the
  next step closes the lone leg before doing anything else, whatever the edge says. A single option leg is
  never held as if it were a straddle.
- **Exit**: the edge back inside half the band (or through zero), or the expiry within `max_days_to_expiry`.
- **Hedge**: whenever the valuation's net delta exceeds `hedge_band` shares, shares are traded to flatten it,
  before the straddle logic runs; after a close this flattens the hedge too.
- **Honesty**: the quotes are Cboe's 15-minute-delayed feed, so part of any "edge" is staleness; the band must
  exceed that noise. Every decision (`WAIT`, `HOLD`, `BUY_STRADDLE`, `SELL_STRADDLE`, `CLOSE`, `*_REJECTED`) is
  recorded with the market and reference vols, the edge and the orders sent or refused, and shown on the
  paper-trading panel and in the log as `[VOL SPREAD] ...`. Positions go through the same gates as any order;
  a generated (synthetic) chain yields SIMULATED quotes that the default policy will not trade.

Configuration (defaults): `vol_edge 0.01`, `reference_model SSVI`, `hedge_band 50`, `min_days_to_expiry 14`,
`max_days_to_expiry 7`, `contracts 1`, `option_interval_seconds 30`.

## 2. Momentum (`strategy.mode: momentum`): shares on a price move

`execution/StrategyExecutionLoop`. It is deliberately simple; its job is to exercise the order path end to
end, not to make money.

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

## 3. Backtesting the momentum strategy

`risk/PortfolioBacktestOrchestrator` runs the same loop over a list of `OptionSnapshot` mid prices and reports
signals, accepted and rejected orders, net quantity, P&L, average slippage and maximum drawdown. `Main` runs it
once at startup over a four-snapshot demo series. See [BACKTEST.md](BACKTEST.md) for the limits of that engine.

---

## 4. Research backlog (not implemented)

These are candidate strategies, kept here as notes. None has code, tests or data behind it.

- **Volatility arbitrage / skew trading**: trade the gap between market implied volatility and a fitted
  parametric surface (the SSVI and SABR models in `volatility/` would be the fitted side). Needs a real option
  chain feed with timestamps and a calibration step; the vol-spread strategy above is a first, single-strike version of this.
- **Delta-neutral market making** with an Avellaneda-Stoikov style quote width
  `half-spread = (gamma/2) sigma^2 (T - t) + (1/gamma) ln(1 + gamma/kappa)` and inventory skew. Needs quoting
  and cancel support in a transport; the paper adapter fills every order in full.
- **Dispersion / correlation arbitrage** between index options and constituent options:
  `sigma_index^2 = sum w_i^2 sigma_i^2 + 2 sum_{i<j} w_i w_j sigma_i sigma_j rho_ij`. Needs multi-symbol chains.
- **Dividend / ex-date arbitrage** using implied dividends from put-call parity,
  `D = S - K e^{-rT} - (C - P)`; the PDE pricer already handles discrete cash dividends, the data feed does not
  supply reliable dividend schedules (see [DATA.md](DATA.md)).
