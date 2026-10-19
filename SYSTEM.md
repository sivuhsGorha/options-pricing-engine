# What the system does (SYSTEM.md)

A living description of the system as it runs today: what it does, what is real and what is simulated, what
state it is in, and what is known to be missing. It is updated with each change that alters behaviour; the
change log at the end says when. Details live in the linked documents, not here.

**Last updated:** 2026-10-14 · **Build:** 488 tests, JaCoCo gate green · **Orders go to:** an Alpaca *paper* account only

---

## 1. In one paragraph

A single Java process watches one underlying (SPY), fits volatility surfaces to its option chain, compares the
market's implied volatility of the front-month at-the-money straddle with the fitted surface, and when the gap
is wide enough sends paper orders through a stack of safety gates to an Alpaca paper account. It keeps a ledger
of every fill, values the book against the surface every few seconds, checks risk limits, and serves a web
dashboard. It never touches real money and it states where each number came from.

## 2. What is real and what is not

| Part | Status |
| :--- | :--- |
| Pricing, Greeks, surface fitting, risk maths | Real, tested code |
| Spot prices | Real, from Finnhub and Alpha Vantage, **delayed**, no bid/ask |
| Option chain | Real, Cboe's public **delayed** feed (about 800 quotes); a synthetic fallback is always labelled |
| Orders | Real orders to an Alpaca **paper** account; nothing reaches an exchange, nothing is real money |
| Fills | Real paper fills from Alpaca; the in-process simulator is the alternative transport |
| Exchange connectivity, latency | **None.** No exchange feed, no latency figure measured or claimed |
| Margin | An approximation, not an exchange model |

Every quote carries a status: LIVE, DELAYED, STALE, UNAVAILABLE or SIMULATED. See [DATA.md](DATA.md).

## 3. How a decision becomes an order

1. **Data in.** The option chain is reloaded every 60 s and the surface refitted every 15 min by default, every 5 min in the local config (SSVI, SVI, SABR; a fit takes about 25 s).
   The spot comes from the configured providers.
2. **Decision.** Every 30 s the vol-spread strategy compares market and reference vol for the front-month
   at-the-money straddle. Inside the larger of the 1.00-point band and the cost hurdle (round-trip half-spread
   plus fees, divided by vega) it holds; outside it buys (market cheap) or sells (market rich) the straddle and
   delta-hedges with shares. Each decision is logged with its reason. With `fair_vol_model` set, a hold also shows
   a realized-vol forecast and the gap to it, as a readout only.
3. **Stand-down rules.** The strategy waits, and sends nothing, when the spot is STALE or unavailable, when an
   option quote is STALE or one-sided, when the chain is simulated or disagrees with the spot, or when no
   surface exists. A lone straddle leg is flattened before anything else.
4. **Order gates.** Halt flag, data-quality policy, portfolio admission (projected Greeks against limits),
   pre-trade limits (size, notional, concentration, spread, volume). See [RISK.md](RISK.md).
5. **Transport.** Alpaca: a day limit order at the venue's touch, wait up to 10 s, cancel the remainder, wait
   for the cancel to settle, then book what filled. See [EXECUTION.md](EXECUTION.md).
6. **Book.** The fill is written to `data/fills.csv` before memory changes. The book is rebuilt from the ledger
   at start and reconciled with the Alpaca account every minute; a mismatch halts trading.
7. **Risk and display.** The book is marked every 5 s, P&L and calibrations are sampled to disk, and the
   dashboard shows it all behind a login. HALT, RESUME and a strategy switch are the operator's controls.

## 4. Current state

| Item | State |
| :--- | :--- |
| Position | Short 20 SPY (paper); reconciled with Alpaca |
| Strategy mode | `vol_spread`; trigger band 1.00 vol point plus the cost hurdle; one contract per leg; `strategy.side` is `long_only` (it buys straddles and holds instead of selling) |
| Running as | Docker container with a restart policy; the `engine-data` volume holds the ledger |
| Data providers | Finnhub and Alpha Vantage delayed; Polygon/Massive off (plan has no snapshot endpoint); Marketstack rate limited |
| Live evidence | 2026-10-09: two fills, one cancel-then-fill race, one reconciliation halt. 2026-10-13: the stale-spot gate held on live data (175 waits), 98 decisions all inside the band, no orders, book matched Alpaca (see EXECUTION.md section 5) |
| Open question | Option quotes read STALE in a third of the steps on 2026-10-13 and a quarter on 2026-10-14. At the open the feed's own timestamp lags by 2 to 6.5 minutes (12 stale waits with no standby near them, ages 127 to 381 s, no reload failure logged), so the 120 s limit is tight for it. Later stale runs cannot be told from the laptop's standbys. Whether to loosen the limit is undecided |
| Running since 2026-10-14 11:28 UTC | The build with the recorder, cost hurdle, attribution, parity-forward fit and the HAR shadow readout. Seen so far, market closed: the recorder wrote a first snapshot and skipped the unchanged feed after it; 250 daily closes loaded from Alpaca's bars; `/api/pnl/attribution` answers (flat). Not yet seen with the market open |
| Off by default | `use_multi_leg` (Alpaca multi-leg buy order; never sent), `fair_vol_model` (shadow only) |
| Not yet seen live | A vol-spread fill; a straddle fill with its hedge; the unvalued-position gate under a real fill; a whole session. 2026-10-13 and 2026-10-14 each recorded only the first 90 minutes (13:30 to about 15:00 UTC) because of laptop standby and an evening restart |

## 5. Known limits

- **Delayed data.** Edge is measured on 15-minute delayed quotes, so it includes staleness; the band must exceed
  that noise. Finnhub's print lags Alpaca's book by 30 to 50 cents in a moving market.
- **No market calendar.** The strategy stands down on a stale spot rather than on the clock, so it also stands
  down on holidays and halts, but it has no notion of session times.
- **Round trip.** The trip to Alpaca from the development machine takes 3 to 5 s, so a 10 s fill wait leaves the
  order resting for about 5 s.
- **The laptop is the weakest link.** It entered Modern Standby 14 times in three hours on 2026-10-14 and was restarted at 16:58 local, so two sessions in a row recorded 90 minutes of 390. See RUNBOOK, Running on a laptop.
- **One process, one underlying.** No redundancy; state is files under `data/`. The ledger, config and keys are
  not in git: `scripts/backup.ps1` and `restore.ps1` cover the data volume and `config.yaml`; `.env` must be kept in a
  password manager (RUNBOOK, "Backup and recovery"). No backup is scheduled; one has to be run by hand.
- **No TLS.** The dashboard binds to localhost; reach it through an SSH tunnel ([RUNBOOK.md](RUNBOOK.md)).
- **Hosting.** Verified on Docker Desktop on Windows only, not on a Linux server.
- **A short straddle may not be placeable on Alpaca at all.** Alpaca's documentation says a multi-leg order must
  cover its short legs inside the order, and a short straddle is two uncovered short options. Whether it accepts
  them as single legs on this paper account has not been tried; the strategy can decide to sell one.
- **No evidence of edge yet.** The reference is a surface fitted to the same chain, so most of the edge is fit
  residual and staleness. The realized-vol forecast and the backtester exist to test a real signal, but the
  recorder only started collecting data on this build, and there is no replay command.
- **The straddle's IV uses the assumed rate and dividend.** The surface now uses the options' own forward, but the
  strategy inverts its straddle with the assumed ones; averaging the call and put cancels the difference to first
  order, so it is small, and it has not been measured.

## 6. Where to look next

[README](README.md) architecture and diagram · [EXECUTION](EXECUTION.md) order path and live session log ·
[RISK](RISK.md) gates · [DATA](DATA.md) providers · [STRATEGY](STRATEGY.md) · [RUNBOOK](RUNBOOK.md) operating it ·
[ROADMAP](ROADMAP.md) what is next.

---

## Change log

Newest first. One line per change that alters what the system does or what we know about it.

- **2026-10-15** The dashboard now shows what the book is worth at the current marks beside what it cost at the fill: a `VALUE (AT MARK)` column in the positions table and `AT MARK` on the book-notional line, both `--` while a held position has no mark (a partial sum would read as the whole book). Display only; P&L already used the marks.
- **2026-10-19** Added a supervised test straddle that runs inside the live engine: `POST /api/control/test-straddle` with `{"confirm": true|false}` (false is a dry run, and the flag has no default). It buys one long front-month ATM straddle through the same gates, Alpaca quotes, transport, tracker and ledger as a signal trade, so the fill is booked where the reconciliation can see it (unlike `--test-buy-straddle`, which bypasses the engine). Refused outside 09:30-16:00 New York on weekdays, with a stale spot, when `strategy.side` is `short_only`, or while a straddle is open. The next ordinary step then manages it, and with no edge it will usually close it within about 30 s. Tested against fakes only; not yet run against the Alpaca paper account.
- **2026-10-15** Added a decision journal and a session report. Every strategy decision is appended to `data/decisions.csv` (in the data volume, so it survives a rebuild and is in a backup; the console log reached back only to the last rebuild, which is how the 10-13 pre-open detail was lost), and `--session-report [date]` summarises a day from it: decisions, holds, waits by cause, stale-quote ages, edge on holds, the RV readout, gaps and coverage, and the day's P&L. A failing journal write is counted and ignored, never a reason to stop trading. Checked on real strings by rebuilding 59 of today's decisions from the container log. 574 Java and 92 front-end tests; not yet running in the container (a Java change, so it needs a rebuild).
- **2026-10-15** Three dashboard wordings corrected after a screen was read closely: the attribution caption now always says its figures run `since the engine last started` (they restart with the engine, so they differ from `Today` above them); the notional column and the book notional are labelled `(at fill)` because they are valued at the last fill price and do not move with the market (the P&L uses the mark and is unaffected); and the empty-orders hint now says a straddle opens only past both the edge band and the round-trip cost, and with `long_only` only when the market is cheap. 560 Java and 92 front-end tests.
- **2026-10-15** The system has its own identity instead of the template's, and its name is **Blackthorn Sterling** (the code had called it AURA-OPT until now): a mark (a V, a long straddle's payoff, under arcs spreading from the strike) as the favicon, the login screen logo and the header, a two-line lockup (`docs/logo.svg`) at the top of the README, the tab `Blackthorn Sterling · options pricing & paper trading`, the npm package `blackthorn-sterling-dashboard`, and `brand.test.js` to keep it so. The name replaces the old one in the UI, the console banners, the alert titles, the User-Agent strings, the Alpaca client order ids (`blackthorn-...`) and the docs. Left alone: the repository, jar, Docker image and volume names (the volume holds the ledger) and the `web-react/` folder. `AURA-OPT Architecture.pdf` is your file and still carries the old name. The unused Vite template sprite is gone and the console banner no longer calls a paper-trading project an institutional execution platform. 560 Java and 90 front-end tests; not yet running in the container.
- **2026-10-15** Removed the unused `opra/` decoder (five files, three tests; no production code used it, and the README says unused gateway code was removed) and deleted the local `v1.0.0` tag, which had never been pushed. 560 Java and 83 front-end tests.
- **2026-10-15** A second batch of code from Gemini was checked against the repository, the machine and a fresh build, and part of it was corrected (EXECUTION.md, section 5). The replay was invalid (it walked a recorded file's several expiries as consecutive moments, priced positions at the wrong strike, and seeded the forecaster with a sine wave) and now groups by moment, marks at the position's own strike, uses real daily closes and refuses to run without them. `strategy.side` is validated and a typo is an error. Alpaca leg quotes are labelled DELAYED / ALPACA_INDICATIVE, not LIVE. The alert service no longer logs its webhook URL or topic, and the docs say it alerts on a trading halt and a graceful shutdown only (the stale-data alert is not called by anything). `--test-buy-straddle` is a dry run unless `--confirm` is given, because the order it sends bypasses the engine's ledger and would make the reconciler halt trading. The unverified Level 4 claim was removed; the infra, opra and tag decisions remain yours. None of it has run in a live session.
- **2026-10-14** Session written up (EXECUTION.md, section 5): 113 holds and no orders in the 88 minutes the engine saw, attribution adding up exactly, the realized-vol readout on every hold (RV 7.2 %, VRP +5.3 to +5.7 points), the recorder at 106 files and 3.8 MB. The cause of the missing sessions and of the 10-13 interruptions was found in Windows' event log: Modern Standby and an evening restart, not a fault in the engine. The stale-quote question is half answered: genuine feed lag at the open, then indistinguishable from standby.
- **2026-10-14** Added `scripts/backup.ps1` and `scripts/restore.ps1` and a RUNBOOK recovery section: what to do
  after a lost laptop, a lost ledger or a new machine. Tried on Windows: a real backup, a restore into a scratch
  volume with the ledger hash matching, a refusal to restore over existing files, and a refusal on a manifest
  mismatch. The rehearsal caught two bugs in the first versions (mangled shell quoting that left the ledger fields
  empty while reporting success, and a fatal error on "no such volume"). `.env` is deliberately not backed up.
- **2026-10-14** The dashboard now shows what the reviewed batch computes: a WHY P&L MOVED block (delta,
  gamma/theta, vega, other) under the P&L figures, and a CHAIN RECORDER status in the footer fed by a new
  `components.recorder` in `/api/health`. The strategy's decision text (cost hurdle, feed age, RV forecast) already
  reached the execution panel. 536 Java and 81 front-end tests.
- **2026-10-14** Reviewed, corrected and wired a batch of code written by Google Gemini (not by this project's
  usual workflow), with 45 new tests (534 in all, without the OPRA package which was left out). Wired and on by
  default: the chain-snapshot recorder (now skips an unchanged feed and no longer loses the last queued snapshot at
  shutdown), the cost hurdle (now counts the exit as well as the entry), the P&L attribution (history now bounded),
  and a parity forward in the surface fit (rewritten: the discount factor is no longer regressed from American
  options, which misread early-exercise premium as a rate error several times too large). Wired but off:
  multi-leg orders (now refuse any short leg, name `position_intent`, and book fills at the venue's net price),
  and a realized-vol shadow readout with real daily closes. Fixed in the library: a HAR forecast with under 22 days
  no longer silently becomes an EWMA; a fair-vol reference with no provider is refused at start instead of
  quietly using SSVI under another name; the VRP backtester now fills at the touch with fees and dates trades from
  the snapshots. Left out: `data/opra/` (an unused decoder for a feed the project does not have; the README says
  the gateway simulations were removed because nothing used them).
- **2026-10-14** The surface-history panel header now says how the latest fit differs from the one before it
  (`last fit vs 5 min earlier: ATM -0.36, skew -0.16`, or `unchanged` when a refit returns the same surface, as it
  does overnight). Front end only, computed from the points the panel already loads; 67 frontend tests.
- **2026-10-14** Local config now refits the surface every 5 minutes instead of 15 (`market_data.refresh_interval_seconds: 300`;
  the default is unchanged). Reason: the strategy compares one-minute-fresh quotes with a reference surface up to
  15 minutes old, so a vol move shows up as edge that is partly the reference lagging. Not yet seen to matter in a
  live session; the 2026-10-13 edges were small (-0.54 to +0.01 points).
- **2026-10-14** A STALE option-quote wait now says how old the feed's stamp was and the limit, so the next
  session can show whether the staleness is feed lag or a failed reload. The engine was found to have been
  interrupted twice on 2026-10-13 (a four-minute gap at 14:25 and a stop at 15:01), cause unknown.
- **2026-10-14** Session log for 2026-10-13 written: no orders in the session, stale-spot gate confirmed live, and
  an unexplained rate of STALE option quotes recorded as an open question. The engine stopped at about 15:02 UTC
  for a reason not yet known.
- **2026-10-13** The vol-spread strategy now waits while the spot is STALE. Found live: between 10:39 and 10:45 UTC,
  before the open, it sent five buy orders for a call on a chain that read fresh while the spot was 16 hours old;
  Alpaca filled none. Fixed in `182db2e` with a test.
- **2026-10-13** Docker hosting verified on Docker Desktop: image builds, ledger replays from the volume,
  reconciliation OK, mounted `config.yaml` in effect. `config.yaml` is now mounted into the container.
- **2026-10-13** Dashboard CSP allows `blob:` images so the chart's PNG download works (untested in a browser).
- **2026-10-10** Hardening pass: unvalued positions fail admission, liquidity gate wired, HMAC covers the body,
  unknown config keys are errors, dead code removed. Not yet exercised at a market open.
- **2026-10-09** First live Alpaca paper session; venue touch from Alpaca's own quote, cancel settles before
  booking, option chains reloaded every 60 s.
