# Case study: building a paper-trading engine, and finding out whether it makes money

Blackthorn Sterling is a Java 25 options pricing and paper-trading engine with a React dashboard. This page says what was built, what running it
live taught, and what the evidence does and does not show. It was built with AI assistance (Claude Code) over three weeks (189 commits,
30 September to 21 October 2026); every claim below points at a test, a log or a data file in this repository, and the numbers come from there.

## What it does

- **Pricing and volatility.** Black-Scholes-Merton, a trinomial tree and a Crank-Nicolson PDE for early exercise; SVI, SSVI and SABR surfaces fitted to
  live option chains; a forward from put-call parity; implied-volatility solvers; a realised-volatility forecast.
- **A trading loop against an Alpaca paper account.** Every order passes pre-trade gates (size, notional, concentration, spread and volume) and portfolio
  risk admission, is sent as a limit order, and is booked in a fill ledger that survives restarts. The engine's book is reconciled with the account every
  minute, and a difference halts trading.
- **Multi-leg orders.** A straddle is opened as one atomic order and closed as one, falling back to closing leg by leg if the atomic order does not fill.
- **Operations.** A decision journal and daily session reports; a dashboard with HALT and RESUME; phone alerts for halts, restarts, the open and close, and
  every trade; a Docker setup with a restart policy; configuration validation that rejects unknown keys in the safety-relevant sections.
- **Checks.** 685 tests, a 75% coverage gate, a secret scan, and a CI pipeline that runs all of it on every push.

## What running it live found

Backtests and unit tests passed throughout. Running against a real paper account on 19 to 21 October found defects that neither had:

| What happened | Cause | Fix |
| :------------ | :---- | :-- |
| An order to sell back a leg was refused, leaving a lone long call for about 100 seconds | The spread limit meant to judge entries was also applied to exits | An order that only reduces a position skips the spread and volume limits; all other limits still apply |
| A valid control request answered 400 | A signed request's body was kept as an exchange attribute, which on JDK 25 is shared by every request to that path, so a later request read the wrong body | The body is handed back through the exchange's own stream. The tests ran on JDK 26, which does not share it, so the regression test uses a fake exchange that follows the documented contract |
| Trading was halted for about 4.5 hours by a mismatch that lasted a second | The reconciliation read the engine's book and Alpaca's between a fill and its booking | The reconciliation takes the lock every order holds from submission to booking |
| An atomic close was accepted by Alpaca and never filled | It was priced at the mid, where a sell is priced at the bid | A close is offered at the touch; the leg-by-leg fall-back covers the rest |
| CI failed on a halt alert test that passed locally | The halt monitor took "was it halted?" as its starting state inside its own thread, so an early halt was swallowed | It starts from "not halted" and reports a standing halt once |

Two analysis errors were caught the same way, by reading the output instead of trusting it: a 61% "volatility" in a study that was a hole in a free data
feed, and a column labelled "worst month" that was the worst window from any start day. Both are fixed and tested.

## What the evidence says about making money

Three studies, each labelled for what it is:

1. **Implied against realised volatility, ten years** (2,693 days, SPY and the VIX). The VIX averaged 18.5 and the volatility that followed 14.7, a
   premium of +3.8 vol points, positive on 84% of days. A short variance position sized at $1 a vol point averaged +2.1 a month with a standard deviation
   of 9.4; the worst month (March 2020) cost 69, about 32 months of average gains.
2. **A straddle on real option prices, held to expiry** (30 monthly trades, early 2024 to 2026, with assumed costs). Buyers paid $2,178 on average and the
   expiry paid $1,866. The buyer averaged -$320 a straddle and the seller +$304, about 1.2 standard errors from zero, and the seller's sign changed by year.
3. **Realised-volatility forecasts, walk-forward** on 343 closes. A simple EWMA beat both a textbook HAR and one fitted to the data (21-day RMSE 3.87
   against 5.26 and 4.40 vol points), so the live readout uses EWMA.

**What the engine itself did.** It placed one trade on its own signal (three straddles, +$12 over about nine minutes), which is a sample of one. The round trips
made to test the order path cost $3 to $19 each in spread. No edge has been demonstrated.

**What this does not show.** Paper fills are kinder than real ones. The studies use daily closes, so costs are assumed. The data is one market and a few
years. The account cannot write uncovered options, so the one pattern in the data, selling premium, is not something this system can do. The honest
conclusion is that the engine is a tested, instrumented piece of software and that the evidence so far does not support trading real money with it.

## What is deliberately not here

No real money, no exchange feed, no latency claims, and no always-on host: the engine runs on a laptop, so it is not running for most of the US session.
Binary-protocol gateway simulations were removed because nothing used them. The decisions that shaped the project, including the ones that turned out
wrong, are in the change log in [SYSTEM.md](../SYSTEM.md); the live sessions are in [EXECUTION.md](../EXECUTION.md).
