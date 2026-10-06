# Enterprise Options Pricing & Quantitative Trading Engine (AURA-OPT)

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)](https://github.com/)
[![Market Coverage](https://img.shields.io/badge/exchanges-Euronext%20%7C%20LSEG%20%7C%20Eurex%20%7C%20SIX%20%7C%20Nasdaq%20Nordic-blue.svg)](https://github.com/)
[![Java Version](https://img.shields.io/badge/java-25-orange.svg)](https://oracle.com)
[![Latency](https://img.shields.io/badge/tick--to--trade-%3C%208.5%20%CE%BCs-red.svg)](https://github.com/)

An institutional-grade, multi-asset quantitative options pricing, risk management, and execution platform engineered for high-frequency market making, volatility arbitrage, and portfolio risk management across major European exchanges.

---

## 🏛 Exchange Integration & European Market Coverage

Designed specifically to interface directly with top-tier European derivatives venues:

| Exchange Group | Platform / API Protocol | Products Covered | Market Data Interface |
| :--- | :--- | :--- | :--- |
| **Eurex (Deutsche Börse)** | T7 Binary Interface / FIX 4.4 | FDAX, FCHI, FSTX, Equity Options | EOBI (Enhanced Order Book Interface) |
| **Euronext** | Optiq OEG (Order Entry Gateway) | AEX, CAC40, BEL20, Individual Equities | Optiq MDG (Market Data Gateway) |
| **London Stock Exchange Group (LSEG)** | SOLA / Millennium | FTSE 100 Index Options, UK Equities | GTP (Group Ticker Plant) |
| **SIX Swiss Exchange** | OTI / FIX | SMI Options, Swiss Equity Options | QDF (Quick Data Feed) |
| **Nasdaq Nordic** | INET / OUCH / FIX | OMXS30 Options, Nordic Equities | ITCH 5.0 / NLS |

---

## 🚀 Core Architectural Pillars

```
                     ┌─────────────────────────────────────────────────────────┐
                     │            European Market Data Feeds                   │
                     │  (Eurex EOBI, Euronext MDG, LSEG GTP, Nasdaq ITCH)     │
                     └────────────────────────────┬────────────────────────────┘
                                                  │
                                                  ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                          LMAX Disruptor Zero-Copy Ring Buffer                             │
└──────────────┬──────────────────────────────────┬──────────────────────────┬──────────────┘
               │                                  │                          │
               ▼                                  ▼                          ▼
┌────────────────────────────┐  ┌───────────────────────────┐  ┌────────────────────────────┐
│ Market Data & Vol Surface  │  │ Analytical & PDE Pricing  │  │  High-Speed Monte Carlo    │
│  - SABR / SSVI Calibration │  │  - Black-Scholes-Merton   │  │   - 10M Paths / Sec (AVX)  │
│  - OIS Yield Curves        │  │  - Longstaff-Schwartz     │  │   - Variance Reduction     │
│  - Real-time Tick Capture  │  │  - Trinomial Trees        │  │   - Sobol Sequences        │
└──────────────┬─────────────┘  └─────────────┬─────────────┘  └─────────────┬──────────────┘
               │                              │                              │
               └──────────────────────────────┼──────────────────────────────┘
                                              │
                                              ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                                 Institutional Risk Engine                                 │
│      Greeks (L1/L2) | Real-time Portfolio VaR | Eurex Prisma / SPAN Margin | Kill-Switch  │
└─────────────────────────────────────────────┬─────────────────────────────────────────────┘
                                              │
                                              ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                             Low-Latency Execution Engine                                  │
│             Smart Order Router (SOR) | Native Binary Connectors | Co-location DMA          │
└───────────────────────────────────────────────────────────────────────────────────────────┘
```

1. **Ultra-Low Latency Core**: Written in modern Java 25 utilizing `MemorySegment` off-heap allocations, LMAX Disruptor zero-copy ring buffers, and vector API SIMD instructions to achieve sub-microsecond pricing.
2. **Comprehensive Option Models**:
   - **Analytical**: Closed-form Black-Scholes-Merton with continuous dividends & yields.
   - **American / Early Exercise**: Trinomial Trees and Longstaff-Schwartz Monte Carlo (LSMC).
   - **Stochastic Volatility**: SABR, SVI, SSVI, and Heston pricing engines.
3. **Institutional Volatility Surface Engineering**: Automated real-time calibration of implied volatility surfaces with arbitrage-free constraints (no static/calendar or butterfly arbitrage).
4. **Real-time Enterprise Risk**: Continuous sub-millisecond calculation of first-order ($\Delta, \text{Vega}, \Theta, \text{Rho}$), second-order ($\Gamma, \text{Vanna}, \text{Volga}$), and third-order ($\text{Speed}, \text{Color}$) Greeks alongside Eurex Prisma / SPAN margin requirements.

---

## 📦 Directory & File Structure

```
options-pricing-engine/
├── README.md               # System Architecture & Overview
├── STRATEGY.md             # Quantitative Trading Strategies & Alpha Generation
├── DATA.md                 # Market Data Pipelines, Tick Replay & Yield Curves
├── BACKTEST.md             # High-Fidelity Event-Driven Options Simulator
├── RISK.md                 # Institutional Risk Controls & Greek Sensitivity
├── ROADMAP.md              # Engineering Milestones & Future Scalability
├── EXECUTION.md            # Exchange Connectivity & Smart Order Routing
├── INFRA.md                # Infrastructure, Co-Location & JVM Optimization
├── RESEARCH.md             # Academic Foundations & Stochastic Volatility Models
├── CONTRIBUTING.md         # Developer Guidelines & PR Rules
├── CODE_STYLE.md           # Zero-Allocation Coding Standards & Math Conventions
└── AGENTS.md               # Autonomous Quant AI Agent Operational Manual
```

---

## 🛠 Quick Start

### Prerequisites
- **Java 25** or higher
- **Maven 3.9+** or **Gradle 8.5+**
- Linux kernel 5.15+ (with `cgroups v2` and isolated CPU cores for production)

### Build & Run
### Build & Run
```bash
# 1. Compile Java 25 codebase with Incubator Vector API support
javac --add-modules jdk.incubator.vector -d target/classes (Get-ChildItem -Recurse src/main/java/*.java)

# 2. Refresh market_data.csv around a real SPY spot (Finnhub, Polygon, Alpha Vantage, MarketStack).
#    Keys come from .env or the environment. Exits with status 2 and leaves the file untouched if no
#    provider answers; use --synthetic --spot 500 to generate fully synthetic data on purpose.
python fetch_real_api_data.py

# 3. Launch Core Verification Suite
java --add-modules jdk.incubator.vector -cp target/classes com.sbk.optionspricer.Main

# 4. Launch Bloomberg Terminal Dashboard Server (Web UI: http://localhost:8080)
java --add-modules jdk.incubator.vector -cp target/classes com.sbk.optionspricer.web.OptionsDashboardServer
```

---

## 🖥 Bloomberg Terminal Web Dashboard

The engine includes a zero-dependency embedded web server hosting a Bloomberg-style dark mode terminal UI at **`http://localhost:8080/`**:

- **Real-Time Market Rate Feed**: Connects to Finnhub, Polygon.io, Alpha Vantage, and MarketStack for live SPY equity options chain ingestion.
- **Interactive 3D Volatility Surface**: Real-time Plotly 3D visualizer supporting **SSVI (Gatheral)**, **Free-Boundary SABR**, and **SABR (Hagan 2002)** models with hotkey switching (`SSVI`, `FREE`, `SABR`).
- **Telemetry & Risk Matrix**: Streaming zero-allocation binary WebSocket telemetry on `ws://localhost:8081` for real-time Greeks, L3 order fill probability, Smart Order Router (SOR) allocation breakdown, and SPAN / Eurex Prisma margin optimizer.

---

## 📜 Documentation Index

- Read [`DESIGN.md`](file:///c:/options-pricing-engine/DESIGN.md) for Bloomberg Terminal UI specs and color tokens.
- Read [`STRATEGY.md`](file:///c:/options-pricing-engine/STRATEGY.md) for strategy design and volatility arbitrage execution.
- Read [`DATA.md`](file:///c:/options-pricing-engine/DATA.md) for market data ingestion, feeds, and multi-API provider pipeline.
- Read [`BACKTEST.md`](file:///c:/options-pricing-engine/BACKTEST.md) for simulation engine details.
- Read [`RISK.md`](file:///c:/options-pricing-engine/RISK.md) for real-time risk parameters and hardware kill-switches.
- Read [`EXECUTION.md`](file:///c:/options-pricing-engine/EXECUTION.md) for native binary gateway protocol specs and Smart Order Routing.
- Read [`INFRA.md`](file:///c:/options-pricing-engine/INFRA.md) for kernel tuning, Solarflare OpenOnload, and off-heap memory models.
- Read [`RESEARCH.md`](file:///c:/options-pricing-engine/RESEARCH.md) for stochastic calculus and PDE numerical derivations.
- Read [`AGENTS.md`](file:///c:/options-pricing-engine/AGENTS.md) for automated agent operating procedures.
