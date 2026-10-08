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
bid for a sell; the order's own price without a book), under the OCC symbol for an option. The transport polls
the order for up to `execution.fill_wait_seconds`; a fill comes back at Alpaca's average price, a partial fill
is booked as `PARTIALLY_FILLED` and the remainder is cancelled, no fill is a rejection naming the wait, and
Alpaca's own rejections (options level, buying power) come back verbatim. `AlpacaPaperClient` has the paper
URL as a constant: no setting can point it at a live account. If a cancel fails the result says the order may
still be working, and the reconciliation below catches any later fill.

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
