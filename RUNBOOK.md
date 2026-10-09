# Runbook (RUNBOOK.md)

One page for whoever runs this. Everything here is observable from the dashboard, `/api/health` or the log.

---

## Start, check, stop

```bash
# Local JVM
java -jar target/options-pricing-engine-1.0.0-SNAPSHOT.jar --check-config        # exit 0 = config and env are valid
java --add-modules jdk.incubator.vector -jar target/options-pricing-engine-1.0.0-SNAPSHOT.jar
# Ctrl+C stops it cleanly: the surface service, engine, mmap state and server close in order.

# Docker
docker compose up --build -d && docker compose logs -f
docker compose down
```

Dashboard: http://127.0.0.1:8080 (sign in with `OPERATOR_PASSWORD`). WebSocket feed: port 8081.
Ready when the log prints `Options Trading Dashboard Live at:`; the surface calibrates afterwards in the
background and the panel badge shows `LOADING` until it does.

## Health

`GET /api/health` (session cookie or HMAC) returns:

| Field | Meaning |
| :--- | :--- |
| `status` | `ok`, `degraded` (market data not fresh, surface failed, or trading halted) or `down` (engine risk state unreadable) |
| `components.marketData` | quote status (`LIVE`, `DELAYED`, `STALE`, `UNAVAILABLE`, `SIMULATED`), age in ms, source |
| `components.riskState` | `READY` when the engine's mmap state is readable |
| `components.surface` | calibration `LOADING` / `READY` / `FAILED`, message, source, whether it is a `DEMO` |
| `components.trading` | `halted`, `haltReason`, `strategyEnabled` |
| `providers` | last known status per market-data provider, without probing |
| `uptimeSeconds` | since the server started |

## Symptoms and causes

| You see | Cause | What to do |
| :--- | :--- | :--- |
| Banner `MARKET DATA FEED UNAVAILABLE`, all providers `UNAVAILABLE` | no provider answered: no network, or no keys | check connectivity; `--check-config` lists which keys are set |
| `POLYGON unavailable: credentials rejected (HTTP 403)` | key invalid or plan does not cover the endpoint | fix the key on polygon.io; the app retries after 15 minutes |
| `rate limited (HTTP 429)` | free-tier quota | nothing; the app backs off (60 s) and stays inside each plan's budget |
| Banner `STALE` | last price older than its window: 30 s for a LIVE source, 120 s for a DELAYED one (outside US hours this is normal) | orders are correctly blocked; wait for market hours or a live source |
| Surface badge `DEMO · SYNTHETIC` | the Cboe chain fetch failed (network, or `cdn.cboe.com` unreachable), so the chain is generated | hover the badge: the tooltip says why; the surface refreshes every `refresh_interval_seconds` |
| Surface badge `FIT · CBOE_DELAYED` | normal: the surface is fitted to Cboe's 15-minute-delayed quotes | nothing |
| Surface badge `FAILED: ...` | fewer than three usable quotes per expiry, or a provider error | the message names the cause |
| `TRADING HALTED — ...` | an operator pressed HALT, a CRITICAL Greek alert fired, or a fill could not be booked | read the reason; RESUME only after you understand it |
| `TRADING HALTED — book does not match the Alpaca account: SPY: local 10, alpaca 0` | the local book and the Alpaca paper account disagree (a position opened in Alpaca's own UI, a fill that arrived after a failed cancel, or a deleted `fills.csv`) | flatten the position on one side (Alpaca's UI, or trade it locally) until the reconciliation reads OK, then RESUME; resuming before that halts again within a minute |
| TRANSPORT badge `ALPACA · UNREACHABLE` | Alpaca did not answer or rejected the keys (the message says which) | check connectivity and the keys in `.env`; orders are rejected until it answers, nothing is halted |
| `REJECTED: Alpaca rejected the order: HTTP 403: ...` | Alpaca refused on its rules: options level, buying power, a symbol it does not trade | read Alpaca's message; a paper account's options level is set on app.alpaca.markets |
| `REJECTED: ... no fill within 10 s at limit 776.30 (the feed's ask)` | the limit order did not fill in time; the remainder was cancelled at Alpaca. The bracket says where the price came from: `the venue's ask/bid` is Alpaca's own quote; `the feed's` means Alpaca's quote was unavailable or stale and a lagging feed priced the order, which rarely fills in a moving market; `the order's price` means no book at all | normal in a fast or thin market; the strategy retries on its next step. Many `the feed's` rejections in a row: check that `data.alpaca.markets` is reachable |
| No orders for a long time | vol-spread mode trades only when the straddle edge exceeds `strategy.vol_edge` (the strategy row shows the edge); momentum mode only on a move of `strategy.trigger_pct` between two quotes | expected with delayed data; narrow the band or the trigger in `config.yaml` to exercise the path |
| Startup prints `[CONFIG] ...` and exits 2 | configuration error | the message names the line or key; `--check-config` lists all of them |
| Strategy row says `WAIT - no loaded expiry at least 14 days out` | the chains have not loaded yet, or every loaded expiry is too close | wait for calibration (the surface badge turns FIT) |
| Strategy row says `WAIT - ... SIMULATED` or `... disagrees with the live spot` | the chain is the synthetic fallback; the strategy will not trade a generated chain | the surface service retries Cboe within a minute; check `cdn.cboe.com` is reachable |
| Strategy row says `WAIT - ... quote STALE` | the Cboe document the quote came from is older than two minutes. Outside US hours this is normal. During market hours it means the quote reload (`market_data.quote_refresh_seconds`, default 60) is failing or too slow: the console prints `[SURFACE] quote refresh failed, keeping the last chains: ...` | outside hours, wait; during hours, check `cdn.cboe.com` is reachable and that the interval is well under 120 s |
| Strategy row says `HOLD ... edge within the band` | normal: the market is within `strategy.vol_edge` of the reference surface | nothing; the edge is shown so you can judge the band |

## Secrets

`.env` holds `API_SECRET` (>= 32 chars), `OPERATOR_PASSWORD` (>= 12 chars) and the provider keys. To rotate:
change the value, restart. Sessions are in memory, so a restart signs everyone out. Nothing in the repository
or the logs ever prints a secret; if one leaks (pasted output, screenshot), rotate it.

## Data

`DATA_DIR` (default `data/`) is git-ignored and holds everything that must survive a restart; back it up if
you care about the paper-trading history. The startup log reports each file:

| File | Holds | Deleting it |
| :--- | :--- | :--- |
| `fills.csv` | one row per fill with its price; the book, average cost and realised P&L are rebuilt from it | starts the book flat |
| `pnl_history.csv` | the valuation sampled once a minute (and when realised P&L changes); today's P&L and drawdown come from it | loses the P&L history, not the book |
| `surface_history.csv` | one row per model per calibration: front ATM vol, skew, RMSE, quotes used, parameters | loses the surface history chart |
| `shm_state.dat` (`MMAP_STATE_FILE`) | the engine-to-web shared state | recreated at start |

A row in any CSV that cannot be parsed stops startup naming the file and line, on purpose: a record with a
hole in it must not quietly produce a different book.

## Upgrade

```bash
git pull && mvn clean verify && (cd web-react && npm ci && npm run build)   # or: docker compose up --build -d
```
`run_all.ps1` runs the same checks as CI and stops at the first failure.
