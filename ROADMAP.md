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

**Operator controls** (2026-10-07). HALT / RESUME and a strategy ON/OFF switch in the paper-trading panel and
the command bar, backed by POST-only `/api/control` endpoints that require the session and the page's Origin.

**Configuration you can trust** (2026-10-07). Inline comments and quoted strings parse; tabs, block lists,
missing keys and duplicates are errors naming the line; wrong types name the key and value; the validator
covers the strategy and execution keys and reports every problem at once; `--check-config` does the same
from the command line and a bad start exits with the messages, not a stack trace.

**Hermetic tests** (2026-10-07). No test constructs a `LiveSpotProvider` that could read the developer's API
keys or reach the network, and engine tests use their own state file instead of the running application's.
JaCoCo gate raised to 75% (measured 81%).

**Operations** (2026-10-07). `/api/health` reports an overall status and each component (market data, risk
state, surface calibration, trading) plus the last known provider statuses without probing, which also fixes
the header's provider badges (they read a field the old response never had). Ctrl+C closes the server,
the calibration thread and the engine in order. `docker-compose.yml` runs the image with `.env`, published
to localhost only, with persistent state. `RUNBOOK.md` maps symptoms to causes. Not done: a structured-logging
rewrite (one line per event); the current `[TAG] message` lines are greppable and the gain did not justify
the churn this week.

**Frontend tests** (2026-10-07). Vitest and React Testing Library cover the five components, the API helpers,
the formatters and the surface provenance badge (29 tests); `npm test` runs in CI and in `run_all.ps1`.

**Real option chains** (2026-10-07). `CboeOptionChain` reads Cboe's public delayed-quotes feed (every expiry,
bid/ask/IV/volume/OI, no key) and is the default source, so the surface is a `FIT · CBOE_DELAYED` for every
visitor instead of a demo; Yahoo (now `401 Invalid Crumb`) stays available but off by default.

---

## Next: deployment readiness (this week, 2026-10-07 to 2026-10-11)

Goal: a reviewer can clone, run, and trust every number on screen.

All eight deployment-readiness items are done; see the dated entries above.

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
