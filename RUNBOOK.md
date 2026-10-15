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
| `components.recorder` | the option-chain recorder: `status` `ON` / `OFF` / `UNAVAILABLE`, `filesWritten`, `quotesRecorded`, `skippedUnchanged`, `lastWrittenAt`. Informational: it never changes `status` |
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
| Startup prints `[CONFIG] ...` and exits 2 | configuration error | the message names the line or key; `--check-config` lists all of them. A key the application does not read in `risk`, `strategy` or `execution` is an error too, with the nearest known key (`risk.max_dleta is not a setting this application reads (did you mean risk.max_delta?)`), because a misspelt limit would otherwise be ignored and the default applied. The dashboard port is `PORT` in `.env`, not a config key |
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
| `chain_snapshots/SPY/<date>/quotes_<HHmmss>.csv.gz` | every option chain the feed changed (a batch whose feed timestamps equal the last one kept is skipped, so nights and weekends add nothing): bid, ask, the feed's IV, volume, source. Raw material for replay and backtests | loses the history a backtest would use; the engine does not read it back. Measured on 2026-10-14: about 36 KB a file (106 files, 3.8 MB by 15:00 UTC, with the laptop asleep for parts of it). Nothing prunes it; check `du -sh data/chain_snapshots` now and then |
| `shm_state.dat` (`MMAP_STATE_FILE`) | the engine-to-web shared state | recreated at start |

A row in any CSV that cannot be parsed stops startup naming the file and line, on purpose: a record with a
hole in it must not quietly produce a different book.

## Upgrade

```bash
git pull && mvn clean verify && (cd web-react && npm ci && npm run build)   # or: docker compose up --build -d
```
`run_all.ps1` runs the same checks as CI and stops at the first failure.

## Hosting on a server

The compose file publishes the dashboard to `127.0.0.1` only, and the app serves plain HTTP with no TLS.
Keep it that way and reach it through an SSH tunnel: `ssh -L 8080:127.0.0.1:8080 user@server`, then open
http://127.0.0.1:8080. The dashboard can HALT, RESUME and switch strategy, so do not publish the port without
TLS in front and a strong `OPERATOR_PASSWORD`.

Before the first start on a new host:

1. Put `.env` and `config.yaml` next to `docker-compose.yml`. The config is mounted read-only into the
   container; `config.yaml` is not in the image, so without the mount the engine would start on defaults.
2. The ledger lives in the `engine-data` volume, which starts empty. With `execution.transport: alpaca` the
   Alpaca account must agree with it, or reconciliation halts trading at startup. Either flatten the paper
   account first, or copy the existing `data/` (above all `fills.csv`) into the volume before starting:
   ```bash
   docker compose create
   docker cp data/. "$(docker compose ps -aq options-engine):/app/data/"
   docker run --rm -u root -v <project>_engine-data:/app/data --entrypoint sh options-pricing-engine:local \
     -c "chown -R appuser:appgroup /app/data"      # the engine runs as appuser and must be able to write
   ```
3. `docker compose up --build -d`, then check `docker compose logs` for `[RECONCILE] OK` and `/api/health`.

Start it with `docker compose up -d`, never with the Run button in Docker Desktop. Run starts a new container from
the bare image, without `.env`, `config.yaml` or the `engine-data` volume, so it crashes on `API_SECRET is missing`
(and, had it started, would have run the simulator against an empty ledger). Remove any such stray container.

`restart: unless-stopped` brings the engine back after a crash or a reboot; the ledger replay and
reconciliation run again on every start.

Verified on 2026-10-13 with Docker Desktop on Windows (the market was closed, so no order was placed): the image
builds, the container reports healthy, the log shows `3 fills replayed, 1 open positions`, the Alpaca transport
(so the mounted `config.yaml` is in effect) and `[RECONCILE] OK`, and `/api/health` answers after sign-in. Not
yet tried: a real Linux VPS, or a fill placed from inside the container.

## Backup and recovery

What the engine needs, and where each piece lives:

| Piece | Lives in | In git? | If it is lost |
| :--- | :--- | :--- | :--- |
| Code, tests, docs | GitHub | yes | `git clone`; nothing else is needed to rebuild |
| `.env` (API keys, `API_SECRET`, `OPERATOR_PASSWORD`) | the project folder | **no, on purpose** | the engine will not start. Keep a copy in a password manager. The provider keys can be regenerated from the providers' own sites; `API_SECRET` and the password are yours to choose again |
| `config.yaml` | the project folder | no | rebuild it from `config.example.yaml` (SYSTEM.md lists the local changes), or restore the copy in a backup |
| `fills.csv` (the ledger) | the `engine-data` Docker volume | no | the book is rebuilt from it at every start. Without it the engine starts flat while Alpaca still holds the position, and the first reconciliation **halts trading**. See "If the ledger itself is lost" |
| `pnl_history.csv`, `surface_history.csv`, `chain_snapshots/` | the same volume | no | history only; the engine runs without it |

### Back up

```powershell
.\scripts\backup.ps1 -Destination D:\aura-backups
```

It writes `D:\aura-backups\<UTC time>\` with `data.tar.gz` (the whole volume), a copy of `config.yaml` and a
`manifest.txt` (commit, ledger row count, ledger SHA-256). It reads the volume read-only, so the engine can keep
running, and it never deletes or overwrites anything. It does **not** copy `.env`. Run it after every session, and
keep the backups somewhere that survives the laptop: a USB drive or a private cloud folder, not the same disk.
The default destination is `%USERPROFILE%\aura-opt-backups`, which is the same disk, so choose another one.

### Restore on a new machine

1. Install Docker Desktop and Git. `git clone https://github.com/sivuhsGorha/options-pricing-engine` and `cd` into it.
2. Put `.env` back from the password manager and `config.yaml` from the backup folder (or rebuild it).
3. `docker compose build` (the restore script uses the image for `tar`).
4. `.\scripts\restore.ps1 -BackupDir D:\aura-backups\<UTC time>`. It refuses to restore into a volume that already
   holds files, and it compares the restored ledger with the manifest (it fails if they differ).
5. `docker compose up -d`, then check `docker compose logs` for `N fills replayed` and `[RECONCILE] OK`.

To try a restore without touching the live volume, add `-Volume some_scratch_name`, then `docker volume rm` it.

### If the ledger itself is lost

Alpaca's account is the source of truth. Either flatten the paper account on Alpaca and start with an empty
ledger, or recreate `fills.csv` from Alpaca's order history, one row per fill
(`timestamp,symbol,executedQty,multiplier,price`, quantity signed, as on 2026-10-09 in EXECUTION.md). The
reconciliation check on the next start says whether the rebuilt book matches the account.

### If the laptop is lost or stolen

`.env` is plain text, so treat every key in it as exposed. The Alpaca keys reach a paper account only, but still:

1. Regenerate the Alpaca paper API keys, and the Finnhub, Alpha Vantage and Massive keys, at their sites.
2. Choose a new `API_SECRET` and `OPERATOR_PASSWORD`.
3. Check Alpaca's order history for anything you did not place.
4. Turn on disk encryption (BitLocker) on the replacement.

Verified on 2026-10-14 on Windows with Docker Desktop: a backup of the live volume, a restore into a new scratch
volume (files owned by the engine's user, ledger SHA-256 equal to the manifest), a refusal to restore into a volume
that already held files, and a refusal when the manifest did not match. Not tried: starting the engine on a restored
volume on a second machine, or restoring from a cloud folder.
## Running on a laptop

Two things stopped the engine from seeing most of the US session on 2026-10-13 and 2026-10-14 (EXECUTION.md,
section 5), and neither is a code fault:

- **Sleep.** On this machine the Balanced power plan puts the laptop into Modern Standby after 5 minutes without
  input on mains power. Docker and the engine pause with it, so the record has holes and the option quotes go stale
  across them. The US session runs 13:30 to 20:00 UTC, which is 15:30 to 22:00 in South Africa.
- **Reboots and shutdowns.** After a restart Docker Desktop does not start by itself, so the engine stays off until
  someone opens Docker Desktop. Its restart policy then brings the container back.

To keep it running through a session:

1. Settings, System, Power: set "Screen and sleep" on mains power to **Never** for the session (or run
   `powercfg /change standby-timeout-ac 0`; restore it with `powercfg /change standby-timeout-ac 5`). Leave the
   charger in. On battery the same sleep rule applies, so a laptop running on battery will still stop.
2. Docker Desktop, Settings, General: tick **Start Docker Desktop when you sign in**.
3. Do not restart or shut the laptop down before 22:00 local, and close the lid only if the lid action is set to
   "Do nothing".
4. After any gap, the log's timestamps show it: `docker compose logs -t --since 2h`.

A laptop that sleeps is a poor host for an engine that is meant to watch a market all day. The VPS route in "Hosting
on a server" is the real fix; this section only reduces the gaps until then.