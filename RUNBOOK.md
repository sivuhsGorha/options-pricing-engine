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
| Banner `STALE` | last price older than 30 s (outside US hours with delayed sources this is normal) | orders are correctly blocked; wait for market hours or a live source |
| Surface badge `DEMO · SYNTHETIC` | the Cboe chain fetch failed (network, or `cdn.cboe.com` unreachable), so the chain is generated | hover the badge: the tooltip says why; the surface refreshes every `refresh_interval_seconds` |
| Surface badge `FIT · CBOE_DELAYED` | normal: the surface is fitted to Cboe's 15-minute-delayed quotes | nothing |
| Surface badge `FAILED: ...` | fewer than three usable quotes per expiry, or a provider error | the message names the cause |
| `TRADING HALTED — ...` | an operator pressed HALT, a CRITICAL Greek alert fired, or a fill could not be booked | read the reason; RESUME only after you understand it |
| No orders for a long time | the strategy fires only on a move of `strategy.trigger_pct` between two quotes | expected with delayed data; lower the trigger in `config.yaml` to exercise the path |
| Startup prints `[CONFIG] ...` and exits 2 | configuration error | the message names the line or key; `--check-config` lists all of them |

## Secrets

`.env` holds `API_SECRET` (>= 32 chars), `OPERATOR_PASSWORD` (>= 12 chars) and the provider keys. To rotate:
change the value, restart. Sessions are in memory, so a restart signs everyone out. Nothing in the repository
or the logs ever prints a secret; if one leaks (pasted output, screenshot), rotate it.

## Data

`data/` holds the fill ledger and the mmap state file (`MMAP_STATE_FILE`). It is git-ignored; back it up if
you care about the paper-trading history. Deleting it loses the ledger and nothing else.

## Upgrade

```bash
git pull && mvn clean verify && (cd web-react && npm ci && npm run build)   # or: docker compose up --build -d
```
`run_all.ps1` runs the same checks as CI and stops at the first failure.
