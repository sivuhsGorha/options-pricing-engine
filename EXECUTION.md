# Execution (EXECUTION.md)

How an order moves from signal to booked position. The only live execution paths are the in-process paper
simulator and an Alpaca paper account. There is no exchange-protocol code: the gateway simulations were removed
(section 3).

---

## 1. Components

```
StrategyExecutionLoop â”€â”€> OrderManager.submit(order, marketSnapshot)
                              â”‚  1 TradingHalt
                              â”‚  2 MarketDataPolicy (status + age)
                              â”‚  3 PortfolioRiskAdmission
                              â”‚  4 PreTradeRiskFilter (incl. concentration, liquidity, rate)
                              â–¼
                     ExchangeTransport.transmit â”€â”€> PaperTradingExecutionAdapter (full fill, slippage)
                                               or  AlpacaPaperTransport (day limit at the touch, fill polled)
                              â”‚
                              â–¼  fill validated (0 <= filled <= requested)
                     PositionTracker.applyFill(symbol, signedQty, multiplier, price)
                              â”‚
                              â”œâ”€> ConcentrationLimitManager.recordFill
                              â”œâ”€> audit trail (bounded, /api/execution)
                              â””â”€> FillRecorder, written before the book changes (FillLedger under DATA_DIR in live mode,
                                  NONE in backtests); the book is rebuilt from it at start
```

**Order** is a record: client id, side, quantity, limit price. **OrderStatus**: `NEW`, `ACCEPTED`, `REJECTED`,
`PARTIALLY_FILLED`, `FILLED`, `CANCELLED`. `OrderManager` keeps open orders and a bounded status map (10,000
most recent) and supports `cancel(orderId)` for the unfilled remainder of a working order (local state only:
no transport leaves an order working at the venue, see below).

**Instruments.** A snapshot for an option carries its `Instrument`; its symbol is the OCC contract symbol
(`SPY261120C00780000`, see `instruments/OccSymbol`) and the position is booked under that symbol with the
contract's own multiplier (100), while the pre-trade notional and concentration count against the underlying
(`SPY`) together with any shares. A stock snapshot has no instrument and uses `execution.contract_multiplier`.
`/api/positions` decodes contract symbols into underlying, expiry, strike and type.

**Money and prices.** Notional is `quantity x price x multiplier` everywhere (risk filter, admission,
concentration, booking), with the multiplier from the instrument when there is one. Prices crossing the wire are converted with `execution/PriceScale` to
exact decimal ticks of 0.0001 (HALF_EVEN), never by truncating `price * 10000`.

**Transports** (`execution.transport`). `paper` (`PaperTradingExecutionAdapter`): a buy fills at the ask (or
bid if no ask) moved up by `execution.slippage_bps`; a sell at the bid moved down; quantity always fills in
full; nothing leaves the process. `alpaca` (`AlpacaPaperTransport`, keys `ALPACA_KEY_ID` and `ALPACA_SECRET`
in `.env`): each order goes to the Alpaca **paper** account as a day limit order at the touch (ask for a buy,
bid for a sell), under the OCC symbol for an option. The touch is Alpaca's own latest quote (stocks from the
IEX feed, options from the indicative feed, read from `data.alpaca.markets` with the same keys) when it is less
than 30 s old; otherwise the feed's book, and without one the order's own price. The result names which was
used (`at limit 776.50 (the venue's ask)`). The venue's quote matters because the spot feed's print lagged the
venue by 30 to 50 cents in the first live session, and a limit pegged to it sat outside the book (section 5).
The transport polls the order for up to `execution.fill_wait_seconds`; a fill comes back at Alpaca's average
price, a partial fill is booked as `PARTIALLY_FILLED` and the remainder is cancelled, no fill is a rejection
naming the wait and the limit, and Alpaca's own rejections (options level, buying power) come back verbatim.
After a cancel the transport waits up to 5 s for the order to leave its working states, because a fill can
complete while the cancel is in flight; an order that filled in full in that window is booked in full
(`filled before the cancel took effect`). `AlpacaPaperClient` has the paper URL as a constant: no setting can
point it at a live account. If a cancel fails or does not settle, the result says the order may still be
working, and the reconciliation below catches any later fill.

**Reconciliation** (`BookReconciler`, Alpaca only): at start and every minute the local book is compared with
the account's positions (signed quantity per symbol, options under their OCC symbol). Any difference trips
`TradingHalt` with a reason naming each position and both quantities, and trips it again if an operator resumes
while the difference persists; an unreachable account is reported, not treated as a mismatch. The halt is never
cleared automatically. `/api/control` and `/api/health` carry the transport block (`transport`, `venue`,
`description`, `checkedAt`, `reconciliation`: `NOT_APPLICABLE` / `PENDING` / `OK` / `MISMATCH` / `UNREACHABLE`,
`differences`), and the paper-trading panel shows a TRANSPORT badge with it.

**Threads.** An order waits for the venue (up to 15 s with the Alpaca transport), so strategy steps do not run on
the 10 ms engine tick that publishes risk and checks the Greek limits. They run on their own thread, at most one
step in flight; a tick that finds the previous step still running skips it. The audit trail has its own lock, so
the order tape answers while an order is at the venue. After every booked fill the order manager runs a
listener (the valuation service's revalue in the application), so a new option position is priced at once.

**Market data policy.** `strict()` (default) accepts LIVE and DELAYED quotes no more than 120 s old (by its own timestamp a LIVE quote turns STALE after 30 s and a DELAYED one after 120 s).
`allowSimulated()` (`ALLOW_SIMULATED_DATA=true`) adds SIMULATED. STALE and UNAVAILABLE are never tradable and
the policy constructor refuses to make them so.

**Failure handling.** An impossible fill quantity, or a fill that cannot be booked, trips `TradingHalt`;
the order is marked and every later order is rejected until an operator resumes. The audit trail records
accepted and rejected orders with the data status and the rejection reason.

---

## 2. API

| Endpoint | Returns |
| :--- | :--- |
| `GET /api/execution` | the audit trail: order id, accepted, source data status, rejection reason, fill price, quantity, time |
| `GET /api/positions` | `halted`, `haltReason`, and positions (symbol, quantity, multiplier, notional) |
| `GET /api/risk` | engine risk state (from the mmap file) plus the execution book's exposure; 503 while the engine state is unavailable |
| `GET /api/control` | `halted`, `haltReason`, `haltedAt`, `strategyEnabled`, `symbol`, `triggerPct`, `baseQuantity` |
| `POST /api/control/halt` | body `{"reason": "..."}` (optional); trips `TradingHalt`, returns the new state |
| `POST /api/control/resume` | clears the halt, returns the new state |
| `POST /api/control/strategy` | body `{"enabled": true\|false}`; switches order generation, returns the new state |
| `GET /api/valuation` | the mark-to-market of the book: per position mark and its source, average cost, unrealised P&L, implied vol and its source, delta/gamma/vega/theta/rho; portfolio totals; or `ready: false` with the reason |
| `GET /api/pnl` | realised, unrealised and total P&L since the ledger began, and today's P&L, peak, trough and maximum drawdown over the New York trading day (measured from yesterday's last mark) with the day's sampled series; `ready: false` until the first valuation is recorded |
| `GET /api/surface/history?model=SSVI\|SVI\|SABR&hours=24` | one point per calibration of the model in the window (at most 168 h): time, spot, source, quotes used, RMSE, front-expiry ATM vol (strike = spot) and skew (vol at 0.95 spot minus vol at 1.05 spot); `ready: false` with the reason while nothing is recorded |

All endpoints require a session cookie or HMAC-signed request and set security headers (see INFRA.md). The
state-changing endpoints accept POST only, and a cookie-bearing request must also carry an allowed `Origin`
header, so a page on another site cannot trip or clear the halt through the operator's browser.

---

## 3. Removed: the gateway simulations

Earlier versions carried a `gateways/` package (a decoder for a made-up 37-byte EOBI-like packet layout, a FIX
New Order Single encoder, a market-data replay into a ring buffer, TWAP slicing and a queue-position estimate)
and `execution/SmartOrderRouter` (a fixed-layout binary payload loosely modelled on Eurex ETI). They were
simulations, labelled as such, and nothing on the order path called them: a review on 2026-10-10 found them
referenced only by their own tests, so they were deleted together with those tests and the ring buffer they fed.
They remain in git history. Nothing in this repository connects to Eurex, Euronext, LSEG, SIX or Nasdaq, and no
latency figure has been measured.

---

## 4. What a real transport would need

An `ExchangeTransport` implementation with: session management and heartbeats, order acknowledgement and
execution reports (partial fills), cancel and replace, cancel-on-disconnect, reconciliation of positions against
venue drop-copy, and certification against the venue's test environment. The Alpaca paper transport covers
acknowledgement, polled execution reports, cancel and position reconciliation over REST against a broker's
paper environment; nothing has been tested against a real exchange.

---

## 5. Live session log

What the system did against the Alpaca paper account, with the numbers as recorded. Times are UTC (New York
is UTC-4). Nothing here is simulated; the account, order ids and fills can be checked on app.alpaca.markets.

### 2026-10-09: first fills, first reconciliation halt

**Setup.** SPY, `execution.transport: alpaca`, `fill_wait_seconds: 10`. Momentum mode with a 0.001 % trigger (a
test value, chosen so that the first ticks after the open would produce orders; the default is 0.1 %). Spot from
Finnhub, labelled DELAYED, with no bid or ask published. Surface fitted to 960 Cboe quotes. Book empty,
reconciliation OK at start. The engine ran the code at commit `10fb649`, before the two changes below.

| Time | Order | What happened |
| :-- | :-- | :-- |
| 13:29:34 | 1, buy 10 | Rejected before the venue: spot was the previous close, status STALE |
| 13:30:35 | 2, buy 10 | Limit 776.29 at Finnhub's last print; no fill in 10 s; cancelled. Alpaca received it at 13:30:40, five seconds after it was created: the round trip from this machine ate half the wait |
| 13:31:27 to 13:33:50 | 3, 4, 5, buy 10 | Limits 776.35, 776.25, 776.70, all at the feed's last; none filled. At 13:33:07 the feed said 776.25 while Alpaca's book was 776.55 / 776.58: a buy limit 30 cents under the bid cannot fill in a rising market |
| 13:34:20 | 6, sell 10 | **Filled in full at 776.64.** The market had risen through the feed's stale print, so the sell was marketable. First row in `data/fills.csv` |
| 13:35:51 | 7, sell 10 | 9 filled at 776.5211 when the wait expired; cancel accepted; the tenth share filled at 776.57 **1.5 ms after** the single confirmation fetch. Booked as a partial of 9; Alpaca's average 776.526 for 10 |
| 13:36:16 | reconciler | `local -19, alpaca -20`: **trading halted** within the minute, naming the symbol and both quantities |
| 13:36:20 | 8 | Refused with the halt reason |

**Operator actions.** The missing share was appended to `fills.csv` at the venue's price (776.570001, so the
ledger's average matches Alpaca's), the mode was set back to `vol_spread` and the trigger to its default. The
account ended the session short 20 SPY at an average of 776.583.

**What it showed.** The gates, the transport, the ledger, the partial-fill path and the reconciliation halt all
behaved as designed, and two of them exposed real defects in the design:

1. **A limit at a delayed print is not "the touch".** The feed's last trade lagged the venue by 30 to 50 cents.
   In a trend that makes every momentum order non-marketable (a buy sits under the bid, a sell over the ask);
   the only fills came when the market moved through the stale price, which is adverse selection by
   construction. The transport now prices the touch from Alpaca's own latest quote and falls back to the feed
   only when that is unavailable or older than 30 s.
2. **A cancel is not a terminal state.** One fetch after the cancel is a race against the venue; the order
   completed 1.5 ms later. The transport now waits for the order to settle (up to 5 s) before reporting, and an
   order that filled in full during the cancel is booked in full. The reconciler remains the backstop; it was
   the backstop here, and it worked.
3. **The option quotes were stale for thirteen minutes in every fifteen.** After the restart (13:49, in
   vol-spread mode) the strategy printed `WAIT - SPY261106C00779000: quote STALE` with the market open. Cboe's
   document was fresh when fetched (its timestamp was 16 s old when checked at 13:54), but it was fetched only
   at each 15-minute calibration, and a quote is STALE 120 s after the feed stamped it. The console confirmed
   the shape of it: thirty `WAIT - quote STALE` steps, then, right after the 14:06 refit, the strategy's first
   real decisions, `HOLD 2026-11-06 779: market 12.3% vs ref 12.0% (edge +0.32 pts) - edge within the
   1.00-point band`, twice, and STALE again within the minute. The chains now reload every
   `market_data.quote_refresh_seconds` (60 by default) on the calibration thread, without refitting, and the
   Cboe document cache was cut from 5 min to 30 s so that a reload actually fetches.

**Restart.** At 13:49 the engine was started again by hand with the position on the book:
`3 fills replayed, 1 open positions`, then `RECONCILE OK: book matches the Alpaca account (1 open positions)`.
The book, its average cost and the P&L came back from the ledger alone.

**After the fixes.** The engine was started on the new build at 14:24 and ran until 14:57 in vol-spread mode:
24 decisions, every one `HOLD` (the front-month 779 and then 780 straddle traded within 0.1 vol point of the
SSVI reference all afternoon, against a 1-point band), and not one `quote STALE` in 33 minutes, where the
morning had produced two usable steps in fifteen minutes. The P&L sampler recorded the short position's
unrealised P&L moving with spot (for example -10.54 at 14:55, -12.14 at 14:56), so the day's P&L and drawdown
are measured from a real mark for the first time.

**Not shown yet.** A fill in vol-spread mode (the edge never left the band), and a full trading day of P&L
samples with a position on the book.

### 2026-10-13: five orders before the open, then a quiet session with no orders

**Setup.** SPY, `execution.transport: alpaca`, `vol_spread` at its 1.00-point band, one contract per leg. The
engine ran in Docker (see RUNBOOK.md). Reconciliation OK at every start, 3 fills replayed, 1 open position (short
20 SPY at 776.583). Option chains from Cboe's delayed feed (799 quotes); spot from Finnhub, labelled DELAYED.

**Before the open (found, fixed).** Between 10:39 and 10:45 UTC, with the US market shut, the strategy sent five
orders to Alpaca (ids 1 to 5): a buy of 1 `SPY261113C00781000` at a limit of 11.26, the feed's ask, on an edge of
-1.01 points against the 1.00-point band. Alpaca filled none; each was cancelled after the 10 s wait and the
second leg was never sent. The cause: the chain is reloaded every minute, so its quotes read DELAYED even with
the market closed, while the spot, 16 hours old and STALE, was never checked. The strategy now waits while the
spot is STALE (commit `182db2e`, with a test). These five orders were read from `/api/execution` at 11:57 UTC;
the container was recreated at 12:09 on the fixed build, so they are no longer in the API or the log.

**The session.** The P&L sampler shows the spot moving from 13:00 to 15:01 UTC (778.1 down to 776.95, 24 and 38
distinct values in the 13h and 14h hours against one value all night). The engine was interrupted twice: the log
goes silent from 14:25:25 to 14:29:42, when the engine starts again from the top (3 fills replayed, reconciliation
OK, a four-minute gap in the P&L samples), and it ends at 15:01:31. Found on 2026-10-14 from Windows' own event log:
the laptop entered Modern Standby at 14:24:53 (which paused Docker and restarted the engine on waking), and the
operating system shut down at 15:01:47. So this entry covers 13:30 to 15:01 UTC, the first 90 minutes of the session, not the
whole day. The tallies below are from `docker compose logs -t`, which timestamps each line; the engine's own
console lines carry none.

| Decisions | Count | What they were |
| :-- | --: | :-- |
| `WAIT - spot quote STALE` | 175 | The new gate. Most of these should be the 80 minutes before the open, but I cannot prove it from an untimed log |
| `HOLD` | 98 | All inside the band: edge from -0.54 to +0.01 points, mean -0.17; the 2026-11-13 straddle at strikes 780 (12), 781 (78), 782 (8) |
| `WAIT - <call>: quote STALE` | 62 | An option quote older than 120 s with a fresh spot |
| `WAIT - <call>: no two-sided quote` | 10 | No bid or no ask on the call |
| `WAIT - no loaded expiry` | 1 | At start, before the chain loaded |

No order was sent, so nothing was filled, rejected by a gate or halted; the book and Alpaca agreed throughout.
The position's unrealised P&L moved with the spot, from -30.34 at 13:00 to -7.34 at 15:01 (range -42.34 to
-7.34), which is 20 shares times the 0.37 between the average cost and 776.95.

**What it showed.**

1. **The stale-spot gate works on live data.** 175 steps waited on the spot instead of reaching the order path.
2. **The option quotes were STALE in 62 of the 170 steps that got as far as them (about a third), and the
   timestamps show the shape.** Spot staleness ends exactly at 13:30. Option-quote staleness is heaviest right
   after the open and fades: in 10-minute buckets from 13:30 the STALE steps were 16, 10, 11, 11, 7, 3, then 1 or
   2 per bucket, while HOLDs rose from 4 to about 20 per bucket (steps are 30 s, so 20 is every step). From 14:19
   the strategy alternates HOLD, WAIT-STALE, HOLD, WAIT-STALE on consecutive steps. That is what a quote whose age
   cycles across the 120 s limit looks like: the age is the time since Cboe stamped the document, the chains reload
   every 60 s, so the age sweeps from the feed's lag L to L + 60 s between reloads. With L near 60 to 90 s the
   quote is fresh right after a reload and stale 30 s later; with L above 120 s it is stale at every step. It
   fits the open, when Cboe's stamp would lag most, but **I have not measured the lag**, so this is a hypothesis.
   The 2026-10-09 afternoon (none in 24 steps) is consistent with a smaller lag then. The log now prints the
   feed's age on every such wait (`quote STALE (feed 150 s old, limit 120 s)`) so the next session settles it.
   Whether 120 s is the right limit for a feed that is delayed by design is a separate question about the
   data-quality policy, and I have not changed it.
3. **The strategy's silence is not evidence that it works.** In 98 decisions the edge never came near the band,
   so the vol-spread open, the straddle fill, the hedge and the unvalued-position gate (hardening of 2026-10-10)
   have still not run against the venue.

**Not shown yet.** A vol-spread fill, a straddle fill with its hedge, and a full session on the new build. Open
question: why the option quotes read STALE a third of the time.

### 2026-10-14: 88 minutes of the session, no orders, and the laptop was the main finding

**Setup.** The build from `e97957d` (the reviewed batch, the attribution block, the recorder status), started at
12:07 UTC in Docker. SPY, `execution.transport: alpaca`, `vol_spread` with the 1.00-point band and the cost hurdle,
the surface refitted every 5 minutes, `fair_vol_model: HAR` as a readout only. Reconciliation OK, 3 fills replayed,
1 open position (short 20 SPY).

**What the engine recorded.** Only 13:30 to 14:58:50 UTC, the first 88 minutes of a six-and-a-half-hour session. The
rest of the day is missing, for two reasons the Windows event log shows:

1. **Modern Standby, 14 times.** Between 12:00 and 15:00 UTC the laptop went into Modern Standby 14 times (about 34
   minutes asleep in all, the longest 11 minutes before the open and 9 minutes at 14:23). A standby can stall the engine:
   the only two gaps in its log that day (3.5 and 4.7 minutes) lined up with standbys, but the 9-minute one left no gap, so
   it does not always. The trigger on this machine is probably the screen, which switches off after 5 minutes on mains
   power, and on a Modern Standby machine that drops the system into standby even when the sleep timer is set to never.
2. **A restart at 14:58:59 UTC (16:58 local).** The PC was restarted from the Start menu. Docker Desktop does not
   start by itself after a reboot, so the engine stayed off until Docker was started by hand at 06:51 the next
   morning, about 16 hours later. 2026-10-13 ended the same way (an OS shutdown at 15:01).

| Steps 13:30 to 14:58 UTC | Count | What they were |
| :-- | --: | :-- |
| `HOLD` | 113 | All inside the band: edge from -0.45 to +0.09 points, mean -0.14. The 2026-11-13 straddle at strikes 775 to 779 (776 for 70 of them) as spot fell from 778 to 772.5 |
| `WAIT - <call>: quote STALE (feed N s old, ...)` | 41 | Feed ages from 123 to 443 s, mean 227 |
| `WAIT - spot quote STALE` | 7 | Brief. Finnhub timed out and Alpha Vantage failed a DNS lookup at 13:28, just before the open; I did not separate the later ones from the standbys |

No order was sent. No fill, rejection, halt or reconciliation mismatch. The ledger still has its 3 fills.

**P&L.** The first sample of the day, -$28.54 at 06:24, to +$82.06 at 14:58:33 as SPY went from 778.01 to 772.48,
which a short 20 SPY gains from. The attribution agrees: at 14:56 the cumulative delta P&L since the engine started
was +$115.80, equal to the change in total P&L, with gamma/theta, vega and other all $0.00. No option is held, so only
delta can explain anything.

**The realized-vol readout.** It appeared on all 113 holds: `RV forecast 7.2%, VRP +5.3 to +5.7 pts`, against a market
at 12.8 to 12.9 %. The premium of implied over realized vol is positive and steady, as expected, which is why the
forecast is shown and does not trade: used as the reference, the strategy would sell every day. The forecast itself is
unverified (the HAR weights are illustrative, and 7.2 % is at the low end for SPY).

**The stale option quotes, split in two.**

- *The first nine minutes (13:31 to 13:39 UTC): 12 stale waits with no standby anywhere near them.* The feed's age climbed
  127, 231, 261 ... 381 s in 30 s steps, then reset to 226 and climbed again. No reload failure was logged. That is
  Cboe's published timestamp lagging by two to six and a half minutes at the open, and it is the part of the 2026-10-13
  hypothesis that holds.
- *After 13:39, indistinguishable from the machine.* 101 of 113 holds and 29 of 41 stale waits fall within ten
  minutes of a standby exit, so the later runs cannot be assigned to the feed or to the laptop.

The 120 s limit is unchanged; whether a feed that is delayed by design should tolerate several minutes of stamp lag is
an open decision.

**The recorder.** 106 snapshot files, 3.8 MB (about 36 KB each) by 15:00, with 59 batches skipped as an unchanged
feed by 14:56. The record has holes where the laptop slept.

**What it showed.**

1. Across the two sessions the strategy has held 211 times with the market open and never decided to open, which
   fits an edge that is mostly the fit's residual. It has still never sent a vol-spread order.
2. The laptop, not the code, is now the weakest link: two sessions in a row covered 90 minutes of 390, and standby makes
   even those 90 minutes uneven.
3. The attribution adds up exactly, the recorder keeps what it should, and the realized-vol readout runs on real data.

**Not shown yet.** A vol-spread fill; behaviour after 15:00 UTC; a session with the machine awake the whole time.
Not done: the Windows power setting and the Docker Desktop start-at-login option (RUNBOOK, "Running on a laptop").

### 2026-10-15: a second batch from Gemini, reviewed: what held and what did not

A report claimed six improvements, "validated against live data", with a tagged release. Checked against the repository,
the machine and a fresh build, it held in part. Nothing from this batch had run live: the container was still the build
of 2026-10-14.

**What held.** The AC sleep and hibernate timeouts are now never (`powercfg`; the battery setting is unchanged), and
Docker Desktop is in the Windows start-up key. The build passed: 547 tests then, 83 front-end tests. The strategy can take
its leg quotes from Alpaca, hold instead of selling when `strategy.side` is `long_only`, and the dashboard can show a
downtime banner. A push-alert service, a buy-straddle test command and a replay command exist. `docs/dashboard.png` is a
real capture, though of a closed market (a STALE banner) and cropped.

**What did not, and what was done about it.**

| Claim | Found | Now |
| :-- | :-- | :-- |
| The replay (-$487,626 on 117 trades; "VRP validates the 211 holds") | Invalid. A recorded file holds every expiry, and the reader returned one snapshot per expiry per instant (476 "snapshots" were 119 moments of about 4 expiries). The backtester walked them as consecutive moments, opening on one expiry and closing against another. It also priced an open position at whichever strike was at the money, closed an unmarked one at its entry price, and seeded the forecaster with `spot * (1 + 0.005 * sin(i * 0.4))`, a sine wave. Its own output said "0 trades filtered by friction" beside a conclusion that friction explained the result | The replay groups snapshots by moment, trades the strategy's own front expiry, marks at the position's own strike and last quotes seen, uses real daily closes from Alpaca strictly before each day, and refuses to run without them. Six tests |
| "Level 3 forbids uncovered shorts (requires Level 4)" | Not supported: Alpaca's options page lists Levels 0 to 3 and mentions neither a Level 4 nor straddles | Removed from the code's decision text and the docs. Whether the account may place a short straddle is still unchecked |
| `strategy.side` | The wiring defaulted to `long_only`, the parameter's own default is `both`, the validator rejected the key as unknown, and a typo fell back to `both` | The key is validated and in `config.example.yaml`, a typo is an error, the default (`long_only`) is stated, and startup prints it |
| Leg quotes "live, millisecond-fresh" | Alpaca's feed here is the indicative one (the repo's own fixture says so), and the code labelled it `LIVE` | Labelled `DELAYED`, source `ALPACA_INDICATIVE`, aged from the quote's own timestamp; three tests |
| Push alerts | The start-up log printed the full webhook URL and the ntfy topic; the stale-data alert is never called by any production code; "engine stopped" only fires on a graceful shutdown | The URL and topic are no longer logged; `.env.example` lists the keys; the docs say alerts cover a trading halt and a graceful shutdown only |
| "Repo decisions settled" | `infra/`, `opra/`, `.gitattributes` and the architecture PDF were committed although they had been left for you to decide, and `v1.0.0` was tagged locally before a clean session | The unused `opra/` decoder was removed (it contradicted the README) and the local `v1.0.0` tag deleted (never pushed). `infra/`, `.gitattributes` and the PDF stay committed, still undecided |

**The corrected replay** on 488 chains at 122 moments over two days (2026-10-14 to 10-15), with 276 real daily closes
and an EWMA forecast: mean ATM VRP +2.66 vol points. The raw level opened one position and lost $60.60 (the spread, the
fees and two days of marking); the demeaned versions opened none and the cost hurdle had nothing to reject. **Two days
say nothing about whether any signal predicts anything**; the run is a check that the mechanics work.

**Not shown yet.** The Alpaca leg quotes, `long_only` and the banner in a live session; `--test-buy-straddle`, which places
a real paper order and has not been run.

### 2026-10-19 and 2026-10-20: five attempts at a supervised straddle, and the first fill

The goal was to prove the live path once: a straddle opened through the strategy, the order manager and Alpaca, booked in the
ledger, reconciled and closed. It took five attempts, and each one found something. All figures are from the engine log, the
ledger and Alpaca's own order list.

| When (UTC) | What was sent | Result | What it showed |
| :--------- | :------------ | :----- | :------------- |
| 19 Oct 13:40 | the 771 call and put as two orders | the call filled at 11.93; the put was refused by the spread gate (315 bps against 200); the engine sold the call back at 11.91 | the unwind works; cost $2.00 |
| 19 Oct 13:43 | the same | the call was refused at 311 bps; nothing sent | Alpaca's indicative spreads swing from tight to wide in seconds (a put read 365, 150 and 8 bps in three reads) |
| 19 Oct 14:13 | the same | HTTP 400 on a valid request body | a bug in the operator API: a signed request's body was kept as an exchange attribute, which on JDK 25 is shared by every request to that path. Fixed and deployed at 14:30 |
| 19 Oct 14:37 | the same | the call buy was accepted by Alpaca and cancelled unfilled after 10 s (limit 12.04) | a limit exactly on the indicative ask misses the real market |
| 19 Oct 14:39 | the same | the call filled at 11.95; the put was refused (204 bps); the sell-back was refused by the same gate (300 bps) | the spread gate also blocked an exit, leaving a lone long call for about 100 s until the strategy's retries filled at 12.11 (+$16, by drift). Exits are now exempt (3610a35) |
| 20 Oct 13:32 | one atomic multi-leg order | refused by the spread gate (a leg at 204 bps); nothing sent | |
| 20 Oct 13:33 | the same, at the touch | accepted by Alpaca (net limit 25.21), cancelled unfilled after 10 s | the multi-leg format works; the limit missed the market again |
| 20 Oct 13:41 | the same with `limit_cushion_bps: 30` (limit 25.54) | **filled at a net 25.27**: call 12.07, put 13.20 | the first complete two-leg open |
| 20 Oct 13:42 | the strategy's own close, 33 s later | call sold at 11.94, put at 13.30 | the close; the round trip cost $3.00 (25.27 paid, 25.24 received) |

**What this shows.** The path works end to end: the order manager, the Alpaca transport (single orders and one atomic multi-leg
order), the fill ledger, the strategy's close, and the unwind of a failed leg. The safety gates held every time, and twice
(an exit blocked by a spread gate, a request body mixed up between requests) the attempt found a real defect, now fixed.

**What it does not show.** No edge: each straddle was bought with no signal and closed with none, and the +$16 and -$3 are drift
and spread. Not seen: the option Greeks on the dashboard while a straddle was open (the window was about 33 s and no snapshot
was taken), and a reconcile with the three positions open (the books matched Alpaca before and after). The engine books a
leg at the fill price Alpaca reports for it only from the fix of 2026-10-20; the straddle above was booked 12.13 and 13.14
against Alpaca's 12.07 and 13.20 (the same total). Another session sent a deliberately unfillable multi-leg order (limit 0.05)
on 19 Oct at 14:49 UTC, cancelled, to check that Alpaca accepts the format.
