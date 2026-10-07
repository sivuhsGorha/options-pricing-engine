# Low-Latency Execution & Exchange Gateway Protocols (EXECUTION.md)

> **Status: design target, not implemented.** The only working execution path is the paper-trading adapter (`execution/PaperTradingExecutionAdapter`) behind `OrderManager`. The exchange protocol layouts, venue routing, kernel bypass and co-location described below are plans or simulations (`gateways/`, `execution/SmartOrderRouter`); there is no connection to any exchange.


This document details the high-frequency execution architecture, exchange binary protocol handlers, Smart Order Routing (SOR) algorithms, and Direct Market Access (DMA) co-location setups across European derivatives markets.

---

## 1. Native Exchange Protocol Integration

To minimize latency, the engine bypasses generic FIX gateways in favor of direct native binary exchange interfaces:

```
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                                   Order Generator Strategy                                │
└─────────────────────────────────────────────┬─────────────────────────────────────────────┘
                                              │ Sub-microsecond Event Pass
                                              ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                             Smart Order Router (SOR) & Pre-Trade Risk                     │
└──────────────┬──────────────────────────────┬──────────────────────────────┬──────────────┘
               │                              │                              │
               ▼                              ▼                              ▼
┌────────────────────────────┐  ┌───────────────────────────┐  ┌────────────────────────────┐
│      Eurex T7 ETI          │  │    Euronext Optiq OEG    │  │       LSEG SOLA            │
│  - Binary TCP/IP Sockets   │  │  - Binary SBE Messaging   │  │  - Binary Native Format    │
│  - Session Management      │  │  - High-Speed Mass Quotes │  │  - Mass Cancel / Quote     │
└──────────────┬─────────────┘  └─────────────┬─────────────┘  └─────────────┬──────────────┘
               │                              │                              │
               ▼                              ▼                              ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                       Solarflare OpenOnload / Kernel Bypass (Onload/EF_VI)                │
└───────────────────────────────────────────────────────────────────────────────────────────┘
```

### Protocol Gateway Matrix

| Exchange | Trading Protocol | Message Encoding | Latency Profile | Key Features Supported |
| :--- | :--- | :--- | :--- | :--- |
| **Eurex (Deutsche Börse)** | ETI (Enhanced Trading Interface) | Native Binary | $< 5.2 \; \mu\text{s}$ | Mass Quote, Cancel-On-Disconnect, Lean Orders |
| **Euronext** | Optiq OEG | Simple Binary Encoding (SBE) | $< 6.1 \; \mu\text{s}$ | Mass Quote, Bulk Cancellation, Fill Notifications |
| **LSEG** | SOLA Native | Native Binary Structs | $< 7.5 \; \mu\text{s}$ | Mass Order Entry, Trade Confirmation |
| **SIX Swiss** | OTI | FIX / Binary | $< 8.0 \; \mu\text{s}$ | Quote Injection, Drop Copy |

---

## 2. Smart Order Router (SOR) & Liquidity Aggregation

When executing hedging orders across correlated instruments (e.g., EURO STOXX 50 futures on Eurex vs CAC 40 futures on Euronext):

1. **Latency-Equalized Routing**: Sends sub-orders with calibrated delay offsets so that orders hit separate exchange matching engines simultaneously.
2. **Probability of Fill Allocation**: Allocates order sizes across venues weighted by level-1 liquidity depth and queue velocity.
3. **Anti-Internalization / Self-Match Prevention (SMP)**: Enforces exchange SMP IDs on all active quotes to avoid self-crossing rules.

---

## 3. High-Frequency Passive Quoting Algorithms

- **Two-Sided Market Making**: Continuously injects mass quotes across option chains.
- **Dynamic Quote Refresh Rate**: Refresh signals are throttled at $\le 100 \; \mu\text{s}$ per contract to comply with exchange quote-to-trade ratio (QTR) limits.
- **Auto-Pull / Cancel-On-Disconnect (COD)**: If the execution gateway loses heartbeat contact with the exchange for $> 50 \text{ ms}$, the exchange matching engine automatically purges all resting quotes.

---

## 4. Hardware Co-Location & Kernel Bypass

Production deployment relies on co-located bare-metal servers installed in exchange data centers:

- **Equinix FR2 (Frankfurt)**: Primary co-location for Eurex (T7) trading.
- **Equinix LD4 (Slough, UK)**: Primary co-location for LSEG & Euronext secondary nodes.
- **Interxion Zurich**: Co-location for SIX Swiss Exchange.
- **Solarflare EF_VI Network Stack**: Direct user-space ring buffer network access bypassing Linux TCP/IP stack overhead entirely.
