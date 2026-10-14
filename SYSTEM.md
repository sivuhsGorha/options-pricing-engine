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
| Strategy mode | `vol_spread`; trigger band 1.00 vol point; one contract per leg |
| Running as | Docker container with a restart policy; the `engine-data` volume holds the ledger |
| Data providers | Finnhub and Alpha Vantage delayed; Polygon/Massive off (plan has no snapshot endpoint); Marketstack rate limited |
| Live evidence | 2026-10-09: two fills, one cancel-then-fill race, one reconciliation halt. 2026-10-13: the stale-spot gate held on live data (175 waits), 98 decisions all inside the band, no orders, book matched Alpaca (see EXECUTION.md section 5) |
| Open question | Option quotes read STALE in about a third of the steps that reached them on 2026-10-13, mostly in the first hour after the open (none in 24 steps on 2026-10-09). Hypothesis: Cboe's own timestamp lags the fetch by 60 to 120 s or more, so the quote's age straddles the 120 s limit. Unmeasured; the log now prints the age |
| Built, not yet run live | The chain-snapshot recorder (data/chain_snapshots), the cost hurdle, the P&L attribution at `/api/pnl/attribution`, the parity-forward surface fit, and the realized-vol shadow readout. All pass their tests (534); none has run against the market |
| Off by default | `use_multi_leg` (Alpaca multi-leg buy order; never sent), `fair_vol_model` (shadow only) |
| Not yet seen live | A vol-spread fill; a straddle fill with its hedge; the unvalued-position gate under a real fill; a full session on the current build (2026-10-13 covered 13:30 to 15:01 UTC) |

## 5. Known limits

- **Delayed data.** Edge is measured on 15-minute delayed quotes, so it includes staleness; the band must exceed
  that noise. Finnhub's print lags Alpaca's book by 30 to 50 cents in a moving market.
- **No market calendar.** The strategy stands down on a stale spot rather than on the clock, so it also stands
  down on holidays and halts, but it has no notion of session times.
- **Round trip.** The trip to Alpaca from the development machine takes 3 to 5 s, so a 10 s fill wait leaves the
  order resting for about 5 s.
- **One process, one underlying.** No redundancy; state is files under `data/`.
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
