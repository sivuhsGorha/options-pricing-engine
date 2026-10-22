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

Six studies, each labelled for what it is:

1. **Implied against realised volatility, ten years** (2,693 days, SPY and the VIX). The VIX averaged 18.5 and the volatility that followed 14.7, a
   premium of +3.8 vol points, positive on 84% of days. A short variance position sized at $1 a vol point averaged +2.1 a month with a standard deviation
   of 9.4; the worst month (March 2020) cost 69, about 32 months of average gains.
2. **A straddle on real option prices, held to expiry** (30 monthly trades, early 2024 to 2026, with assumed costs). Buyers paid $2,178 on average and the
   expiry paid $1,866. The buyer averaged -$320 a straddle and the seller +$304, about 1.2 standard errors from zero, and the seller's sign changed by year.
3. **Realised-volatility forecasts, walk-forward** on 343 closes. A simple EWMA beat both a textbook HAR and one fitted to the data (21-day RMSE 3.87
   against 5.26 and 4.40 vol points), so the live readout uses EWMA.

4. **The engine's own signal on real option prices** (`--chain-replay`: 130 Mondays, February 2024 to September 2026). Each Monday it takes the straddle at
   the nearest strike about 28 days out, backs the implied volatility out of the closing prices and subtracts the same EWMA forecast the live engine reports.
   The edge did not predict what a buyer made: correlation +0.04, a slope of +$12 per point of edge, t-statistic +0.2 on about 33 independent observations.
   Split in thirds by edge, the buyer averaged -$199, -$459 and -$73 (standard errors $320 to $470), no pattern. Buying when the edge was below minus half a point
   averaged -$319 (standard error $495); selling when it was above, which this account cannot do, +$223 (standard error $312). Both are inside their noise.
   This is the question that decides whether the strategy is worth running, and on this data the answer is that the edge it trades on did not predict anything.

5. **Selling defined-risk spreads on real option prices** (`--spread-study`, 31 monthly trades, early 2024 to 2026, with a pass rule written before the run). A put credit
   spread sold 3% out of the money with a $5 width collected $78 on average against a possible loss of $422, and averaged +$3 after assumed costs (t-statistic +0.1, 87% winners,
   worst trade -$462). An iron condor collected $188 against $312 and averaged +$33 (t-statistic +0.8, 73% winners, worst -$374). The weekly versions, which overlap, averaged
   +$15 and -$2. Both lost money in 2025. The rule needs a positive mean, a t-statistic above 2, a positive mean every year and an affordable worst trade: both met the first and
   last and missed the other two, so the verdict is UNPROVEN, not a pass. About thirty trades cannot tell a small premium from zero, and the losses of a premium seller arrive rarely and large.

6. **Trend-following against holding SPY** (`--trend-study`, ten years of daily closes, October 2016 to October 2026, long above the 200-day average and in cash below it,
   5 basis points a switch). The rule was written before the run and calibrated on simulated markets with nothing to find, where it passes about 5% of the time. Result: the
   trend rule returned +8.8% a year against +13.8% for holding, with volatility of 11.8% against 17.7% and a largest fall of 22% against 34%. Its Sharpe ratio was 0.78 against
   0.82, not the 0.4 better the rule asks for, so the verdict is FAIL. It did cut the fall from a peak by a third, and that held in both halves of the data and for the 100, 150
   and 250-day windows, which is the usual trade of trend-following: less return and less pain, not more reward for the risk. Cash earned nothing in the test, which understates
   it slightly: even at 2% a year on the 17% of the time it was out, that is about 0.3% a year, nowhere near enough to change the verdict.

**What the engine itself did.** It placed one trade on its own signal (three straddles, +$12 over about nine minutes), which is a sample of one. The round trips
made to test the order path cost $3 to $19 each in spread. No edge has been demonstrated.

**What this does not show.** Paper fills are kinder than real ones. The studies use daily closes, so costs are assumed. The data is one market and a few
years. The account cannot write uncovered options, so the one pattern in the data, selling premium, is not something this system can do. The honest
conclusion is that the engine is a tested, instrumented piece of software and that the evidence so far does not support trading real money with it.

## What is deliberately not here

Left out on purpose, with the reason, because each would be a large piece of work around a strategy that has not shown an edge: multiple underlyings (the engine is SPY by
design); splitting pricing, risk and execution into separate processes; moving the CSV ledgers into a database (the ledger survives restarts and is read by tests as it is);
a hot standby (it needs a second machine, and there is one laptop); a secret manager such as Vault (it needs a server; secrets are in `.env`, redacted from every line the
engine prints and never committed); a real-time options feed (it costs money); a SPAN or TIMS margin model (the scenario margin is labelled as an approximation); Kelly or
risk-parity sizing, fill-probability and slippage models, and adaptive limits (the fills to fit them to number a handful, and a model fitted to a handful is a guess with a
formula; `executionQuality` in `/api/health` measures what fills cost so a model could be fitted when there is data). A Prometheus server and a signing proxy for scraping
`/metrics` are not included either.

No real money, no exchange feed, no latency claims, and no always-on host: the engine runs on a laptop, so it is not running for most of the US session.
Binary-protocol gateway simulations were removed because nothing used them. The decisions that shaped the project, including the ones that turned out
wrong, are in the change log in [SYSTEM.md](../SYSTEM.md); the live sessions are in [EXECUTION.md](../EXECUTION.md).
