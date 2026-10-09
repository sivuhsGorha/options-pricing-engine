# Execution (EXECUTION.md)

How an order moves from signal to booked position. The only live execution path is paper trading; the
exchange-protocol code in `gateways/` is simulation and is marked as such in every class header.

---

## 1. Components

```
StrategyExecutionLoop ──> OrderManager.submit(order, marketSnapshot)
                              │  1 TradingHalt
                              │  2 MarketDataPolicy (status + age)
                              │  3 PortfolioRiskAdmission
                              │  4 PreTradeRiskFilter (incl. concentration, liquidity, rate)
                              ▼
                     ExchangeTransport.transmit ──> PaperTradingExecutionAdapter (full fill, slippage)
                                               or  AlpacaPaperTransport (day limit at the touch, fill polled)
                              │
                              ▼  fill validated (0 <= filled <= requested)
                     PositionTracker.applyFill(symbol, signedQty, multiplier, price)
                              │
                              ├─> ConcentrationLimitManager.recordFill
                              ├─> audit trail (bounded, /api/execution)
                              └─> FillRecorder, written before the book changes (FillLedger under DATA_DIR in live mode,
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

**Market data policy.** `strict()` (default) accepts LIVE and DELAYED quotes under 30 s old.
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

## 3. Simulations in `gateways/` and `execution/SmartOrderRouter`

| Class | What it really is |
| :--- | :--- |
| `execution/SmartOrderRouter` | writes a fixed-layout binary payload loosely modelled on Eurex ETI into a buffer and passes it to the transport. No venue, no routing between venues |
| `gateways/EobiDecoder` | decodes a made-up 37-byte EOBI-like packet layout into `OrderBookTick`s on the ring buffer |
| `gateways/EobiMarketDataHandler` | generates random packets in that layout on a thread; opens no socket |
| `gateways/FixMessageEncoder` | formats FIX 4.4-style New Order Single strings; no session, no counterparty |
| `gateways/HistoricalReplayEngine` | replays `market_data.csv` into the same ring buffer |
| `gateways/TwapExecutionAlgo`, `QueuePositionEstimator`, `OrderTicket`, `OrderState` | schedule slicing and queue estimates on paper; not connected to the order manager |

These exist to exercise the decoder and ring buffer. Nothing in this repository connects to Eurex, Euronext,
LSEG, SIX or Nasdaq, and no latency figure has been measured.

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

**Not shown yet.** A fill in vol-spread mode, and a full day of P&L samples with a position on the book.
