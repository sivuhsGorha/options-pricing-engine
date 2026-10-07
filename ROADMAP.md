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

## Next: from a pricing dashboard to an options paper-trading system

Dated 2026-10-07. The order is deliberate: each phase makes the previous one's numbers mean more, and every
task ships only with tests that failed first, a clean `mvn clean verify`, and green CI. Estimates are working
days for one person. Nothing here promises exchange connectivity or performance figures.

### Phase A: trade options off the fitted surface (4 to 5 days)

Today the strategy trades SPY shares, so gamma and vega are honestly zero and the Greek alerts, hedge analysis
and halt have nothing real to act on. After this phase the book holds option contracts priced from the fitted
surface and marked to market every tick.

| # | Task | What changes | Tests that must fail first | Done when |
| :-- | :--- | :--- | :--- | :--- |
| A1 (done 2026-10-07) | **Instrument identity on the order path** | `MarketSnapshot` gains an optional `instrument` (OCC symbol, expiry, strike, type, multiplier) built from `instruments/Instrument`; `OrderManager` books fills under the contract symbol with the contract's multiplier (100) instead of the configured default; `/api/positions` shows contract, expiry, strike, type | an option fill is booked under `SPY261120C00780000` with multiplier 100 and notional qty x price x 100; a share fill is unchanged | positions table lists contracts, not just `SPY` |
| A2 | **Option quotes as market snapshots** | `OptionQuoteSnapshotAdapter` turns a Cboe chain quote (bid, ask, mid, volume, feed timestamp) into a `MarketSnapshot` with status `DELAYED`; the same freshness rules apply (120 s against the feed's timestamp) | a quote from the fixture becomes a snapshot with the right book and a `STALE` one when the feed timestamp is old; a zero-bid quote is not tradable | the order gate accepts or rejects option orders for stated reasons |
| A3 | **Mark-to-market service** | `core/PortfolioValuationService` runs after each surface refresh and every N seconds: for each open contract, implied vol from the fitted SVI slice at its strike and expiry, Black-Scholes price and Greeks (`Greeks`, `AnalyticalHigherGreeks`), `PortfolioPosition.updateGreeks`, unrealised P&L against fill prices; theta and rho added to `PortfolioExposure` | a known position gets the Black-Scholes delta/gamma/vega at the surface's vol; a contract past expiry is valued at intrinsic and flagged; no surface means Greeks are reported as `UNAVAILABLE`, not zero | risk panel shows non-zero gamma/vega for an option book, with the valuation time |
| A4 | **Options strategy** | `execution/VolSpreadStrategy` (on/off and parameters via `/api/control`): compares the market implied vol of the front-month ATM straddle with the fitted SVI value; sells the straddle when market IV exceeds the fit by more than `strategy.vol_edge` (default 1 vol pt) and buys it when below; delta-hedges with shares when net delta exceeds `strategy.hedge_band`; one position at a time, closes at `strategy.max_days_to_expiry` | generated chains where the market IV is pushed above the fit produce a sell; below, a buy; inside the band, nothing; the hedge fires only outside the band; everything still passes `OrderManager`'s gates | orders and positions in the dashboard show straddle legs and the hedge, each with its fill or rejection reason |
| A5 | **Risk engine on real Greeks** | `UnifiedQuantEngine` publishes the valuation service's exposure (including theta and rho); `GreekAlertManager` thresholds come from `risk.*` config instead of constructor constants; the margin approximation's gamma term now matters | a book with gamma beyond `risk.max_gamma` trips a CRITICAL alert and the halt; the published mmap state matches the valuation | the halt fires for an options book that breaches a configured limit |
| A6 | **Dashboard and docs** | positions table with contract columns and unrealised P&L; a "VALUATION" badge with the time and surface used; risk panel shows theta and rho; `STRATEGY.md`, `RISK.md`, `EXECUTION.md`, `DESIGN.md` updated | frontend tests for the new columns and badge | a reviewer can see a straddle, its Greeks and its P&L change with the market |

Decisions taken up front: the strategy prices off **SVI** (the closest fit) and reports the edge against SSVI too,
so the two are visible side by side; contract multiplier is read from the instrument, never from config, once A1
lands; expired contracts are settled at intrinsic value against the spot of the day and removed, with a ledger
entry. Risk: Cboe quotes are 15 minutes delayed, so "edge" includes staleness; the strategy's band must be
wider than that noise and the docs must say so.

### Phase B: a broker behind `ExchangeTransport` (2 to 3 days, needs your Alpaca paper keys)

| # | Task | Done when |
| :-- | :--- | :--- |
| B1 | `AlpacaPaperTransport`: submit, acknowledge, poll for fills (partial fills become `PARTIALLY_FILLED`), cancel; keys from `.env` (`ALPACA_KEY_ID`, `ALPACA_SECRET`), paper endpoint only, hermetic tests against recorded responses | an order placed from the dashboard appears in the Alpaca paper account and its fill comes back into the position tracker |
| B2 | Reconciliation: compare the local book with Alpaca positions at start and every minute; a mismatch trips the halt with the difference in the reason | a deliberately mismatched position halts trading and names the contract |
| B3 | Transport selection in config (`execution.transport: paper | alpaca`) and a `TRANSPORT` badge in the UI | the dashboard says which transport is live |

### Phase C: state that survives a restart (1 to 2 days)

| # | Task | Done when |
| :-- | :--- | :--- |
| C1 | Move the fill ledger out of `target/` (which `mvn clean` deletes) into `data/`, SQLite via the JDK-free `sqlite-jdbc` dependency, with positions rebuilt from it at start | stop, start, and the positions table is unchanged |
| C2 | Persist surface snapshots (parameters, RMSE, quotes used) so the surface history can be charted | a "surface history" chart of ATM vol and skew over the day |
| C3 | Daily P&L and drawdown from the ledger on the dashboard | the risk panel shows realised and unrealised P&L since start |

### Phase D: presentation (half a day, needs your screenshot)

| # | Task | Done when |
| :-- | :--- | :--- |
| D1 | `docs/dashboard.png` captured by you; README header image and a 30-second "what you are looking at" caption | the GitHub landing page shows the fitted surface |
| D2 | README "How it works" diagram: Cboe chain -> fitter -> surface -> strategy -> order gates -> paper fills -> risk | one picture a reviewer can follow |
| D3 | Tag `v1.0.0` with release notes generated from this file | the release page lists what is and is not implemented |

### Your actions
- Restore `strategy.trigger_pct: 0.001` in your local `config.yaml` before showing the project (it holds a test value).
- Fix or remove the rejected Polygon key; create a free Alpaca paper account before Phase B.
- Capture the screenshot for D1.

---

## Later (one to three months)

- Historical option chains (a paid or recorded source) so the backtester can replay real surfaces; expiry
  settlement, fees, partial fills and impact; Sharpe, Sortino, drawdown duration.
- Benchmarks (JMH) committed with results before any performance claim is written down.
- Structured one-line logging if operations need it.

---

## Ideas (no date)

Arbitrage-free SABR, Heston pricing and calibration, Longstaff-Schwartz, full SLV calibration, multi-symbol
chains and dispersion strategies, exchange connectivity, kernel bypass and CPU isolation (only after a measured
need), GPU Monte Carlo, learned volatility surfaces.
