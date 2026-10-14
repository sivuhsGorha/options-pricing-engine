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

1. **Data in.** The option chain is reloaded every 60 s and the surface refitted every 15 min (SSVI, SVI, SABR).
   The spot comes from the configured providers.
2. **Decision.** Every 30 s the vol-spread strategy compares market and reference vol for the front-month
   at-the-money straddle. Inside the 1.00-point band it holds; outside it buys (market cheap) or sells (market
   rich) the straddle and delta-hedges with shares. Each decision is logged with its reason.
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
| Open question | Option quotes read STALE in about a third of the steps that reached them on 2026-10-13 (none in 24 steps on 2026-10-09); cause not found |
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

## 6. Where to look next

[README](README.md) architecture and diagram · [EXECUTION](EXECUTION.md) order path and live session log ·
[RISK](RISK.md) gates · [DATA](DATA.md) providers · [STRATEGY](STRATEGY.md) · [RUNBOOK](RUNBOOK.md) operating it ·
[ROADMAP](ROADMAP.md) what is next.

---

## Change log

Newest first. One line per change that alters what the system does or what we know about it.

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
