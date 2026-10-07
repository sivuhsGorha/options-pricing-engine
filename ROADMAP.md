# Roadmap (ROADMAP.md)

Dated 2026-10-07. "Done" means merged to `main` with tests and CI green. Everything else is proposed work with
an honest estimate; nothing below is a promise of a trading venue connection or a performance figure.

---

## Done

**Pricing and models.** Black-Scholes-Merton with dividend yield; accurate normal CDF; safeguarded-Newton
implied volatility; first-, second- and third-order Greeks; Monte Carlo cross-check; trinomial tree;
Crank-Nicolson PDE with Rannacher start-up, Brennan-Schwartz American exercise and discrete cash dividends;
SVI, SSVI (closed-form no-arbitrage conditions), SABR (Hagan), Dupire local volatility; OIS/par-yield
bootstrap with schedules; ACT/365F.

**Execution and risk.** Order manager with ordered gates (halt, data policy, portfolio admission, pre-trade
filter), contract multiplier, exact decimal ticks, bounded audit trail; paper-trading adapter; position
tracker with fill ledger; Greek alerts with hysteresis that trip a trading halt; historical and Monte Carlo
VaR, expected shortfall, margin approximation and optimizer.

**Platform.** Spot providers with gates and budgets; data-status model (LIVE/DELAYED/STALE/UNAVAILABLE/
SIMULATED); mmap shared state with seqlock and correct lifecycle; SPSC ring buffer with correct slot
release; dashboard with login, sessions, HMAC, CSP, risk, surface and paper-trading panels; CI with tests,
coverage gate, Docker readiness, frontend lint/build and secret scanning; 336 Java tests, 11 Python tests.

**Honesty pass.** Dead code removed, simulations labelled, documentation rewritten to match the code.

**Market data without placeholders** (2026-10-07). A field a provider does not supply is unknown (`NaN` book,
`VOLUME_UNKNOWN`), the liquidity gate judges only known fields, paper fills without a book use the order price.

**Surface fitted to the chain** (2026-10-07). `VolatilitySurfaceService` loads chains for four tenors in the
background and fits SSVI and SABR with a Nelder-Mead least-squares fitter; the dashboard shows provenance,
quotes used, RMSE and the market points, and labels a synthetic-chain fit `DEMO`. Startup no longer waits on
the option-chain network call.

---

## Next: deployment readiness (this week, 2026-10-07 to 2026-10-11)

Goal: a reviewer can clone, run, and trust every number on screen.

4. **Operator controls in the UI.** Halt / resume trading (authenticated POST), strategy on/off, and the
   current trigger and size shown in the paper-trading panel.
5. **Config parser hardening.** Inline comments, clear error messages naming the key and line, and a
   `--check-config` mode.
6. **Hermetic tests.** Inject a fake spot provider into the remaining tests that construct `LiveSpotProvider`;
   ratchet the JaCoCo gate to the measured figure minus five points.
7. **Operations.** Structured logging (one line per event, no banners), `/api/health` with per-component
   state, graceful shutdown that closes the mmap and ring buffer, a `docker compose` file with the `.env`
   contract, and a one-page runbook.
8. **Frontend tests.** Vitest with React Testing Library for the five components, run in CI.

---

## Later (one to three months)

- Mark-to-market and Greeks of open positions through the pricer each tick; theta and rho aggregation.
- Backtester with historical chains (Parquet), expiry settlement, fees, partial fills and impact; Sharpe,
  Sortino, drawdown duration.
- A broker paper-trading API as the first real `ExchangeTransport` (acknowledgements, partial fills, cancel),
  with reconciliation against the broker's positions.
- Persistent storage for fills and snapshots (SQLite or Postgres) replacing the in-memory stores.
- Benchmarks (JMH) committed with results before any performance claim is written down.

---

## Ideas (no date)

Arbitrage-free SABR, Heston pricing and calibration, Longstaff-Schwartz, full SLV calibration, multi-symbol
chains and dispersion strategies, exchange connectivity, kernel bypass and CPU isolation (only after a measured
need), GPU Monte Carlo, learned volatility surfaces.
