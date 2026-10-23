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

## Changing config.yaml

The engine reads `config.yaml` once, at start, through a single-file mount (`./config.yaml:/app/config.yaml:ro`). After
you edit it, **recreate the container**:

```powershell
docker compose up -d --force-recreate     # no rebuild; about a minute; then check the startup lines below
```

`docker compose restart` is not enough. Many editors save by writing a new file and swapping it in, which gives the host
file a new identity while the container's mount still points at the old one, so a restart can start the engine on the
**previous** contents. On 2026-10-20 the container showed an older copy of the file than the host after several edits, and
a setting was nearly lost on the next restart. (A change to the code or the image needs `docker compose up -d --build`.)

Check that a setting took effect from the startup lines, not from the file:

| Setting | Startup line to look for |
| :------ | :----------------------- |
| `strategy.use_multi_leg: true` | `[STRATEGY] multi-leg orders are ON` |
| `execution.limit_cushion_bps: 30` | `[EXECUTION] Limits are set 30 bps past the touch` |
| `strategy.fair_vol_model` | `[FAIR VOL] EWMA realized-vol forecast reported beside each hold` |
| any setting | `docker compose exec options-engine cat /app/config.yaml` shows what the container sees |

Run `java -jar target/options-pricing-engine-1.0.0-SNAPSHOT.jar --check-config` first when you can: it reports every bad value at
once, and an unknown key in a safety-relevant section such as `strategy` or `execution` is an error, not ignored. Edit the file from one place at a time: two sessions
rewriting it on 2026-10-20 made a setting appear, vanish and reappear. `config.yaml` is in `.gitignore`, so a change to it
is not in git; the backup (see Backup and recovery) is the only other copy.

## Logs and secrets

Everything the engine prints (the console log that `docker compose logs` shows) passes through one guard before it leaves the process:

- **Secrets are removed.** The exact value of every configured secret (`ALPACA_KEY_ID`, `ALPACA_SECRET`, `API_SECRET`, `OPERATOR_PASSWORD`, `NTFY_TOPIC`,
  `ALERT_WEBHOOK_URL`, `HEARTBEAT_URL`, `TLS_KEYSTORE_PASSWORD` and the provider keys) is replaced by `***` wherever it appears, including inside an
  exception's message or a stack trace, and including a secret printed in pieces. A value under 8 characters is not treated as a secret. This does not
  depend on each of the engine's print statements being careful. HTTP error responses are separate: they only ever use fixed messages, which a test enforces.
- **JSON on request.** `LOG_FORMAT=json` in `.env` turns each line into one JSON object, `{"timestamp","level","component","message","stream"}`, for a log tool;
  the component is the leading `[TAG]`, the level INFO on standard output, WARN on standard error and ERROR for a fatal, urgent, halt or exception line. The
  default is the plain `[TAG] message` lines, unchanged. The setting is read at start.

What the guard does not cover: a secret that is not configured (a value pasted into `config.yaml`, which holds none by design), and anything that never goes
through the console (the files under `data/`, which hold no secrets).

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
| Surface badge `DEMO Â· SYNTHETIC` | the Cboe chain fetch failed (network, or `cdn.cboe.com` unreachable), so the chain is generated | hover the badge: the tooltip says why; the surface refreshes every `refresh_interval_seconds` |
| Surface badge `FIT Â· CBOE_DELAYED` | normal: the surface is fitted to Cboe's 15-minute-delayed quotes | nothing |
| Surface badge `FAILED: ...` | fewer than three usable quotes per expiry, or a provider error | the message names the cause |
| `TRADING HALTED â€” ...` | an operator pressed HALT, a CRITICAL Greek alert fired, or a fill could not be booked | read the reason; RESUME only after you understand it |
| `TRADING HALTED â€” book does not match the Alpaca account: SPY: local 10, alpaca 0` | the local book and the Alpaca paper account disagree (a position opened in Alpaca's own UI, a fill that arrived after a failed cancel, or a deleted `fills.csv`) | flatten the position on one side (Alpaca's UI, or trade it locally) until the reconciliation reads OK, then RESUME; resuming before that halts again within a minute |
| TRANSPORT badge `ALPACA Â· UNREACHABLE` | Alpaca did not answer or rejected the keys (the message says which) | check connectivity and the keys in `.env`; orders are rejected until it answers, nothing is halted |
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
| `decisions.csv` | one row per strategy decision (every 30 s): time, action, expiry, strike, market and reference vol, edge, orders sent, and the reason in the strategy's own words. Unlike the console log it survives a rebuild. The input to the session report | the history of what the strategy decided is gone (the console log only reaches back to the last rebuild); nothing else is affected |
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

`restart: always` brings the engine back after a crash, after Docker restarts and after a reboot, including after a `docker compose stop`, and `stop_grace_period: 30s` gives it
time to finish a clean shutdown; the ledger replay and reconciliation run again on every start. It was `unless-stopped` until 2026-10-23: that keeps a container down after any
manual stop, and the engine then stayed off for two days.

**Running it without a daily command.** For the engine to start by itself when the laptop does, Docker Desktop has to start at sign-in: Docker Desktop, Settings, General, tick
"Start Docker Desktop when you sign in to your computer". After that nothing needs typing: the engine starts with Docker, and stops when Docker does (closing or shutting down the
laptop). `docker compose stop` is then only for stopping it on purpose, and the engine will come back at the next Docker start. This runs the engine only while the laptop is on and awake:
a sleeping or shut-down laptop is a stopped engine, and the heartbeat line at the next start says how long it was away.

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
.\scripts\backup.ps1 -Destination D:\blackthorn-backups
```

It writes `D:\blackthorn-backups\<UTC time>\` with `data.tar.gz` (the whole volume), a copy of `config.yaml` and a
`manifest.txt` (commit, ledger row count, ledger SHA-256). It reads the volume read-only, so the engine can keep
running, and it never deletes or overwrites anything. It does **not** copy `.env`. Run it after every session, and
keep the backups somewhere that survives the laptop: a USB drive or a private cloud folder, not the same disk.
The default destination is `%USERPROFILE%\blackthorn-sterling-backups`, which is the same disk, so choose another one.

### Restore on a new machine

1. Install Docker Desktop and Git. `git clone https://github.com/sivuhsGorha/options-pricing-engine` and `cd` into it.
2. Put `.env` back from the password manager and `config.yaml` from the backup folder (or rebuild it).
3. `docker compose build` (the restore script uses the image for `tar`).
4. `.\scripts\restore.ps1 -BackupDir D:\blackthorn-backups\<UTC time>`. It refuses to restore into a volume that already
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

- **Sleep and standby.** This laptop drops into Modern Standby every few minutes. It can stall Docker and the engine
  (two stalls of 3 to 5 minutes on 2026-10-14 lined up with standbys, and a 17-minute standby on 2026-10-15 left no gap
  at all), so the record may have holes and the option quotes can go stale across them. Setting the sleep timer to never
  is not enough: on a Modern Standby machine the **screen** switching off (5 minutes on mains by default) is what
  starts it, so also set the screen-off timeout on mains to never (`powercfg /change monitor-timeout-ac 0`). The US session runs 13:30 to 20:00 UTC, which is 15:30 to 22:00 in South Africa.
- **Reboots and shutdowns.** After a restart Docker Desktop does not start by itself, so the engine stays off until
  someone opens Docker Desktop. Its restart policy then brings the container back.

To keep it running through a session:

1. Settings, System, Power: set "Screen and sleep" on mains power to **Never** for the session, both the screen and
   the sleep timer (or run `powercfg /change monitor-timeout-ac 0` and `powercfg /change standby-timeout-ac 0`; restore with
   `powercfg /change monitor-timeout-ac 5` and `powercfg /change standby-timeout-ac 5`). Leave the
   charger in. On battery the same sleep rule applies, so a laptop running on battery will still stop.
2. Docker Desktop, Settings, General: tick **Start Docker Desktop when you sign in**.
3. Do not restart or shut the laptop down before 22:00 local, and close the lid only if the lid action is set to
   "Do nothing".
4. After any gap, the log's timestamps show it: `docker compose logs -t --since 2h`.

A laptop that sleeps is a poor host for an engine that is meant to watch a market all day. The VPS route in "Hosting
on a server" is the real fix; this section only reduces the gaps until then.

## Would sizing by forecast volatility have helped? (the volatility-targeting study)

```powershell
java --add-modules jdk.incubator.vector -cp "target/classes;target/lib/*" com.sbk.optionspricer.Main --voltarget-study 0.10 5
```

Arguments: the target volatility as a fraction of a year (0.10 is 10%) and the cost of trading in basis points. It holds `min(1, target / EWMA forecast)` of SPY each day, the rest in cash,
and compares ten years of daily closes with holding, under a rule fixed in advance: the largest fall at most 80% of the market's, a Sharpe ratio at least 0.15 better, at least the
market's in each half, and at least the market's with targets of 8% and 12% and a slower decay. The 0.15 passes about 5% of simulated markets with nothing to find. The run of 2026-10-22
gave FAIL: a much smaller fall and worst day, a lower return, and a Sharpe ratio no better than noise.

## Would trend-following have beaten holding SPY? (the trend study)

```powershell
java --add-modules jdk.incubator.vector -cp "target/classes;target/lib/*" com.sbk.optionspricer.Main --trend-study 200 5
```

Arguments: the moving-average window in days and the cost of a change of position in basis points. Same keys as the other studies; about ten years of SPY daily closes, a few
seconds. It compares being long when the close is above its average and in cash otherwise with holding, and applies a rule fixed in advance: the largest fall at most three
quarters of the market's, a Sharpe ratio at least 0.4 better, a smaller fall in each half of the data and with the 100, 150 and 250-day windows. The 0.4 was chosen so the rule
passes only about 5% of simulated markets with nothing to find. The run of 2026-10-22 gave FAIL: a third less fall from the peak, but a lower return and no better Sharpe ratio.

## Would selling defined-risk spreads have paid? (the spread study)

```powershell
java --add-modules jdk.incubator.vector -cp "target/classes;target/lib/*" com.sbk.optionspricer.Main --spread-study 0.05 0.03 5
```

Arguments: the assumed cost in dollars a share on every leg (once), how far out of the money to sell as a fraction of spot (0.03 is 3%), and the width in dollars. Same keys as the
history study; about 500 read-only calls and a few minutes. It sells a put credit spread and an iron condor on a fixed calendar at the day's closing prices, holds them to expiry,
and applies a pass rule written before the first run: a positive mean after costs, a t-statistic above 2, a positive mean in every year, and a worst trade within $1,000.
PASS, UNPROVEN (positive but not by more than luck) or FAIL is printed per spread. The run of 2026-10-21 gave UNPROVEN for both. It is a screen on closing prices, not a trade signal.

## Does the edge predict anything? (the chain replay)

```powershell
java --add-modules jdk.incubator.vector -cp "target/classes;target/lib/*" com.sbk.optionspricer.Main --chain-replay 0.04
```

Same keys and cost argument as the history study, about 130 calls and two minutes, read-only. Every Monday since February 2024 it takes the straddle at the nearest
strike about 28 days out, solves its implied volatility from the closing prices, subtracts the EWMA realized-volatility forecast the engine reports, and prints
whether that edge predicted what a buyer made held to expiry: the correlation, the slope, the t-statistic, the result by third of edge, and what the engine's rule
(buy below minus half a point, sell above plus half a point) would have made. Mondays 28 days out overlap four at a time, so the standard errors use a quarter of
the days. A t-statistic below about 2 means the edge did not predict the result. It is a screen on closing prices, not a test of the engine's minute-by-minute
trading. The last run (2026-10-21) gave a t-statistic of +0.2: no relationship.

## A straddle on real option prices (the history study)

```powershell
java --add-modules jdk.incubator.vector -cp "target/classes;target/lib/*" com.sbk.optionspricer.Main --option-history-study 0.04
```

Needs `ALPACA_KEY_ID` and `ALPACA_SECRET` in `.env` (read-only: SPY's daily bars and the daily bars of option contracts, about 170 calls and a minute or two).
The argument is the assumed cost in dollars a share on each of the two legs, charged once; 0.04 is about $8 a straddle. It buys a straddle at the nearest
strike on a fixed calendar (the third Friday's expiry bought 30 days before, and every Friday's bought 28 days before), at that day's closing prices, holds it
to expiry, and prints what a buyer made and what a seller would have, with the mean, median, spread, win rate, worst and best trade, the largest fall from a
peak (monthly only: the weekly trades overlap) and the result by year. A trade whose legs have no price that day is counted as skipped, never filled in.
Alpaca's free option history starts in early 2024. It is **not a test of the engine's strategy** (a calendar and hold-to-expiry, not an edge and a quick
exit), the daily close has no bid or ask so the costs are an assumption, and the seller rows are what a seller would have made, which this account cannot do.

## Implied against realised volatility (the premium study)

```powershell
# once: the VIX history (public, from the CBOE), saved where the command looks for it
curl.exe -L -o data/historical/VIX_History.csv https://cdn.cboe.com/api/global/us_indices/daily_prices/VIX_History.csv
java --add-modules jdk.incubator.vector -cp "target/classes;target/lib/*" com.sbk.optionspricer.Main --vrp-study
```

Needs `ALPACA_KEY_ID` and `ALPACA_SECRET` in `.env` (read-only: it fetches SPY's daily bars from 2016) and the VIX file; it stops, without a
table, if either is missing or the two share fewer than 250 days. For each day it sets the VIX against SPY's realised volatility over
the next 21 trading days and prints the premium (VIX minus what followed, in vol points), how often it was positive, the same by year
and by the level of the VIX, and the payoff of a short variance position sized at $1 a vol point when it is struck, rolled every 21 days
(mean, standard deviation, worst month, largest fall from a peak). It is a study of the premium, **not a backtest of any trade**: no
costs, no margin, no options prices, the VIX is the S&P 500's and not SPY's, daily windows overlap, and it covers one market. The
bars come from the SIP feed because the free IEX feed has almost nothing before mid-2020 (none for 2019 or March 2020); a window that
spans a hole of more than a week in the closes is dropped, not read as a quiet month.

## Compare realized-vol forecasts

```powershell
java --add-modules jdk.incubator.vector -cp "target/classes;target/lib/*" com.sbk.optionspricer.Main --forecast-eval
```

Needs `ALPACA_KEY_ID` and `ALPACA_SECRET` in `.env` (read-only: it only fetches daily bars). It scores four forecasts (trailing
22-day, EWMA, HAR with its illustrative weights, HAR fitted on the days known at each point) against the volatility that
followed, 5 and 21 days ahead, and stops without a table if it has no real closes or fewer than 120. Lower RMSE and QLIKE are
better; a negative bias means the forecast ran low. Origins are consecutive days, so the windows overlap and a small gap
between two models is noise. It scores prediction of realised volatility, not options prices or trading profit.

## Replay a recording against a realized-vol forecast

Replays the chains recorded under `data/chain_snapshots` through `VrpSignalBacktester`: trade the strategy's front
expiry (the nearest at least 14 days out) when implied vol is rich or cheap to an EWMA forecast of realized vol, with
fills at the touch and a fee per contract. Run it from the project folder, so that `.env` supplies the Alpaca keys:

```powershell
java --add-modules jdk.incubator.vector -jar target\options-pricing-engine-1.0.0-SNAPSHOT.jar --replay data\chain_snapshots
```

The recordings live in the Docker volume; copy them out first with
`docker cp <container>:/app/data/chain_snapshots <some folder>` and pass that folder.

- **It needs real daily closes.** They come from Alpaca's bars with `ALPACA_KEY_ID` and `ALPACA_SECRET`; each day's
  forecast uses only earlier days. Without them it stops and says so; it never makes a series up.
- **It reports what it covers:** chains, moments (a recorded file holds every expiry, so one moment is several chains),
  and trading days. With fewer than 20 days it prints "TOO FEW DAYS": read the numbers as a check of the mechanics.
- **Three rows:** the raw VRP level, VRP demeaned against its 20-moment average, and the same with the cost hurdle
  (round-trip half-spread and fees over vega). Not modelled: slippage beyond the touch, partial fills, assignment, a
  hedge; the forward and the rate are assumed.

## The paper buy-straddle test (not yet run for real)

`.\scripts\test_buy_straddle.ps1` (or `java ... --test-buy-straddle`) checks the Alpaca multi-leg path with a one-contract
buy straddle at the venue's touch. **By default it is a dry run**: it prints the account, the quotes and the order it
would send, and sends nothing.

With `-Place` (`--confirm`) it sends **one real order to the Alpaca paper account**. That order goes straight through the
Alpaca client, not through the engine, so the engine's ledger never records the fill and its reconciliation halts trading
at the next check until the position is flattened on Alpaca's paper dashboard. So: `docker compose stop` first, run it
only with the market open, and flatten afterwards. Whether the account may place a short straddle is a separate,
still unchecked question; nothing here tries one.
## The supervised test straddle (inside the engine)

Unlike the script above, this runs in the live engine, so the order goes through the order manager and the Alpaca
transport, the fill is booked in the ledger and tracker, and the reconciliation then agrees with Alpaca instead of
halting. It buys **one** long straddle (front-month ATM call and put), needs no edge, and is refused outside 09:30-16:00
New York on weekdays, with a stale spot, when `strategy.side` is `short_only`, and while a straddle is already open.

```powershell
# sign in once (the password is OPERATOR_PASSWORD), then call the control; Origin must be an allowed origin
$origin = 'http://127.0.0.1:8080'
Invoke-WebRequest "$origin/login" -Method Post -Body $env:OPERATOR_PASSWORD -Headers @{ Origin = $origin } -SessionVariable s | Out-Null
Invoke-RestMethod "$origin/api/control/test-straddle" -Method Post -WebSession $s -Headers @{ Origin = $origin } -ContentType 'application/json' -Body '{"confirm":false}'   # dry run
Invoke-RestMethod "$origin/api/control/test-straddle" -Method Post -WebSession $s -Headers @{ Origin = $origin } -ContentType 'application/json' -Body '{"confirm":true}'    # one real paper order
```

After a confirm: the order appears in the dashboard's order tape and on Alpaca's Orders page and the book should still reconcile
(`[RECONCILE] OK`). The next ordinary step manages the straddle like any other and, with no edge to hold it, will usually close it
about 30 seconds later: the test exercises open and close, and costs the spread.

**Run live on 2026-10-20** (after five attempts over two days): with `strategy.use_multi_leg: true` and `execution.limit_cushion_bps: 30`
the straddle went as one atomic order, filled (net 25.27), was booked, and was closed by the strategy 33 seconds later for a net
-$3.00, the spread. A second run the same day (14:09) filled at a net 24.59, was booked at Alpaca's own leg prices (12.09 and 12.50), showed
both option legs marked on the dashboard with their Greeks while open, and cost $19.00 round trip. The close is two separate orders and can leave
one long leg for a few seconds (about 18 s that time). Since then the strategy closes a whole straddle as one multi-leg order (`sell_to_close`
on both legs, offered at the sum of the bids) and falls back to leg by leg if it is not filled; the 14:43 run went in and out as single
orders (24.18 paid, 24.09 received, $9). Not seen: a reconcile line while the positions were open (OK before and after).
Before sending, read the legs' spreads: the engine refuses an entry whose spread is over `risk.max_spread_bps` (200), and Alpaca's
indicative quotes swing from tight to wide within seconds. Each attempt is in EXECUTION.md section 5.

## Push alerts

Set in `.env` (not in git; see `.env.example`): `NTFY_TOPIC` (a long random name: anyone who knows a public ntfy.sh topic can read it) and/or
`ALERT_WEBHOOK_URL` (it usually contains a secret token). Neither value is printed in the log. Alerts go out on:

1. a **trading halt**, within about a second (a limit breach, a reconciliation mismatch, or the operator's HALT), at urgent priority;
2. a **graceful shutdown** of the engine (`docker compose stop` or Ctrl+C);
3. **the engine starting**: "ENGINE STARTED" with whether trading is active, whether the books agree with Alpaca, the positions, the last decision and
   the P&L. One you did not expect means the engine restarted: a reboot, a crash, or a laptop that went to sleep and woke;
4. **a summary shortly after the US open** (09:35 New York) and **after the close** (16:10, the day's session report), on weekdays, once each. There is no
   market calendar, so an exchange holiday still gets both, saying the market is stale; a summary that cannot be built says "status unavailable" instead of
   being skipped, and one that was due while the laptop slept is sent only if the wake is within an hour of its time;
5. **every trade**: when the strategy sends orders, "TRADE: <action>" with the decision and each order and how it ended (a refusal is tagged as a warning).

Messages 3 to 5 are sent only when `NTFY_TOPIC` or `ALERT_WEBHOOK_URL` is set; they come from threads of their own, so a slow or failing phone service cannot
delay a decision.

**To receive them on a phone:** install the ntfy app (Android or iOS), tap +, and subscribe to the topic named after `NTFY_TOPIC=` in `.env` on
the default server `ntfy.sh`. ntfy.sh keeps sent messages for about 12 hours, so a test sent just before you subscribe usually still shows up. The text sent is the one in the
log, for example "Options trading halted: book does not match the Alpaca account: ...": position names and quantities, no keys or passwords.

**To test the whole path** (only with the market closed, since a halt stops trading until cleared): `POST /api/control/halt` with a reason and then
`POST /api/control/resume` (the dashboard's HALT and RESUME buttons do the same). The phone should buzz within seconds; the engine log shows
`[ALERT][URGENT] TRADING HALTED`. If a send fails, the log says `[ALERT] ... not delivered` with the HTTP status or the kind of error, never the
topic or the URL.

**What sends nothing:** a crash, a power loss, a sleeping laptop or a stopped Docker Desktop, because the engine that would send the alert is the thing
that is down. Catching that needs a check from outside the machine: the engine pings `HEARTBEAT_URL` (a free healthchecks.io check, say) every minute and that service
alerts when the pings stop. It works only once you create the check and put its URL in `.env`; until then none is set up. A halt tripped while the phone alert was off is found only by reading `/api/control` or the log, which is how the halt of 2026-10-20 15:22
stayed on overnight. `ALERT_STALE_MINUTES` is read but nothing uses it yet, and there is no market-data-stale alert. The calendar now exists (holidays, 13:00 early closes), so one could be built;
it has not been.

## Downtime banner

When the engine starts at least 30 minutes after its last recorded P&L sample, the dashboard shows
`DOWNTIME GAP: engine was off ...` above the data banner. It is computed once at start-up.

## Session report

`--session-report [YYYY-MM-DD]` prints one UTC day of the strategy's decisions from `data/decisions.csv` (and the day's P&L
from `pnl_history.csv`): how many decisions, how many holds, how often stale data stopped it, how old the stale option
quotes were, the edge it saw on its holds, the realized-vol readout, the age of the spot, the chain and the option quotes at each decision, any gap where the engine recorded nothing, and the
coverage of the 30-second steps the day should have had. With no date it reports the latest day in the journal.

```powershell
docker compose exec options-engine java --add-modules jdk.incubator.vector -jar /app/app.jar --session-report 2026-10-15
```

It states what the journal holds and nothing more: a day with no rows is "no decisions recorded", not "quiet". The journal
starts with the build that added it, so earlier days have no report; those can only be reconstructed from whatever console
log survived.