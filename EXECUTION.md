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
                              │
                              ▼  fill validated (0 <= filled <= requested)
                     PositionTracker.applyFill(symbol, signedQty, multiplier, price)
                              │
                              ├─> ConcentrationLimitManager.recordFill
                              ├─> audit trail (bounded, /api/execution)
                              └─> FillRecorder (FillLedger in live mode, NONE in backtests)
```

**Order** is a record: client id, side, quantity, limit price. **OrderStatus**: `NEW`, `ACCEPTED`, `REJECTED`,
`PARTIALLY_FILLED`, `FILLED`, `CANCELLED`. `OrderManager` keeps open orders and a bounded status map (10,000
most recent) and supports `cancel(orderId)` for the unfilled remainder of a working order (local state only:
`ExchangeTransport` has no cancel operation yet).

**Instruments.** A snapshot for an option carries its `Instrument`; its symbol is the OCC contract symbol
(`SPY261120C00780000`, see `instruments/OccSymbol`) and the position is booked under that symbol with the
contract's own multiplier (100), while the pre-trade notional and concentration count against the underlying
(`SPY`) together with any shares. A stock snapshot has no instrument and uses `execution.contract_multiplier`.
`/api/positions` decodes contract symbols into underlying, expiry, strike and type.

**Money and prices.** Notional is `quantity x price x multiplier` everywhere (risk filter, admission,
concentration, booking), with the multiplier from the instrument when there is one. Prices crossing the wire are converted with `execution/PriceScale` to
exact decimal ticks of 0.0001 (HALF_EVEN), never by truncating `price * 10000`.

**Paper fills** (`PaperTradingExecutionAdapter`): a buy fills at the ask (or bid if no ask) moved up by
`execution.slippage_bps`; a sell at the bid moved down. Quantity always fills in full.

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
venue drop-copy, and certification against the venue's test environment. The order manager's fill validation
and halt-on-mismatch are designed with that in mind, but nothing has been tested against a real venue.
