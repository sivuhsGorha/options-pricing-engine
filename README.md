# Options Pricing & Paper-Trading Engine (AURA-OPT)

[![CI](https://github.com/sivuhsGorha/options-pricing-engine/actions/workflows/ci.yml/badge.svg)](https://github.com/sivuhsGorha/options-pricing-engine/actions)
[![Java Version](https://img.shields.io/badge/java-25-orange.svg)](https://openjdk.org/)

A Java 25 options pricing and risk library with a paper-trading loop and a web dashboard. It prices
options, calibrates volatility surfaces, computes Greeks and portfolio risk, and runs an order flow
(pre-trade checks, risk admission, simulated fills) against live or simulated market data.

**What it is not:** a connection to any exchange. There is no live order entry and no exchange market-data
feed. Orders only ever fill in the built-in paper-trading adapter. The `gateways/` package and
`execution/SmartOrderRouter` are simulations of binary-protocol encoding and decoding (see their class
headers). No latency figure is measured or claimed.

---

## What is implemented

| Area | Implementation |
| :--- | :--- |
| Closed-form pricing | Black-Scholes-Merton with continuous dividend yield; accurate normal CDF; safeguarded Newton implied-volatility solver |
| Early exercise | Trinomial tree; Crank-Nicolson PDE (Rannacher start, Brennan-Schwartz) in log-spot; discrete-dividend PDE pricer |
| Monte Carlo | European Monte Carlo pricer used as a cross-check for the closed form; VaR / expected shortfall calculators |
| Volatility | SVI, SSVI with no-arbitrage conditions and validation, SABR (Hagan) and a free-boundary variant, Dupire local vol |
| Rates | OIS / par-yield curve bootstrap, ACT/365F day count, optional FRED and ESTR providers |
| Greeks and risk | First, second and higher-order Greeks, portfolio aggregation, limit alerts, margin approximation (not an exchange margin model) |
| Execution | `OrderManager` (halt check, data-quality policy, portfolio admission, pre-trade limits, order state machine), `PositionTracker`, paper-trading fills, contract multiplier |
| Dashboard | Embedded HTTP API and a Jetty WebSocket feed, HMAC request signing, browser sessions, React frontend in `web-react/` |
| IPC | Engine state published through a memory-mapped file (seqlock) and read by the web layer |

Market data comes from Finnhub, Polygon, Alpha Vantage, MarketStack and Yahoo Finance when API keys are
configured. Without a live source, quotes are marked `SIMULATED`/`UNAVAILABLE` and orders are rejected
unless `ALLOW_SIMULATED_DATA=true`.

---

## Quick start

Prerequisites: JDK 25, Maven 3.9+, and Node 20+ only if you rebuild the frontend.

```bash
# Build and test (JaCoCo coverage gate included)
mvn clean verify

# Configuration: copy config.example.yaml to config.yaml, and put secrets in .env (never committed):
#   API_SECRET=<at least 32 characters>
#   OPERATOR_PASSWORD=<at least 12 characters>
#   FINNHUB_API_KEY=...  POLYGON_API_KEY=...  (any provider you have)

# Run the engine and the dashboard (http://127.0.0.1:8080, WebSocket on port 8081)
java --add-modules jdk.incubator.vector -jar target/options-pricing-engine-1.0.0-SNAPSHOT.jar

# Optional: refresh market_data.csv from a real provider (exits with status 2 if none answers)
python fetch_real_api_data.py
```

Risk and execution settings (`risk.*`, `execution.*`, `strategy.*`) are documented in `config.example.yaml`.
`execution.contract_multiplier` (default 1) scales notional, limits and positions identically.

---

## Documentation

- [DESIGN.md](DESIGN.md): dashboard UI specification
- [STRATEGY.md](STRATEGY.md): strategy design
- [DATA.md](DATA.md): market-data ingestion and providers
- [BACKTEST.md](BACKTEST.md): backtesting
- [RISK.md](RISK.md): risk parameters and limits
- [EXECUTION.md](EXECUTION.md): order flow and (simulated) gateway layouts
- [INFRA.md](INFRA.md): deployment and JVM notes
- [QUANT_MATH.md](QUANT_MATH.md) and [RESEARCH.md](RESEARCH.md): the mathematics
- [HFT_ARCHITECTURE.md](HFT_ARCHITECTURE.md): design notes for the memory-mapped IPC and vectorised maths
- [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_STYLE.md](CODE_STYLE.md), [AGENTS.md](AGENTS.md): working agreements
