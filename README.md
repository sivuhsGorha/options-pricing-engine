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
| Volatility | SVI, SSVI with no-arbitrage conditions and validation, SABR (Hagan), Dupire local vol; a background service fits SSVI and SABR to the option chain (Nelder-Mead least squares) and the dashboard shows the fit's source, quotes, RMSE and parameters |
| Rates | OIS / par-yield curve bootstrap, ACT/365F day count, optional FRED and ESTR providers |
| Greeks and risk | First, second and higher-order Greeks, portfolio aggregation, limit alerts that halt trading, margin approximation (not an exchange margin model) |
| Execution | `OrderManager` (halt check, data-quality policy, portfolio admission, pre-trade limits, order state machine, audit trail), `PositionTracker`, paper-trading fills, contract multiplier, exact decimal ticks |
| Dashboard | Embedded HTTP API and a Jetty WebSocket feed, HMAC request signing, browser sessions, React frontend with risk, volatility-surface and paper-trading panels |
| IPC | Engine state published through a memory-mapped file (seqlock) and read by the web layer |

Market data comes from Finnhub, Polygon, Alpha Vantage, MarketStack and Yahoo Finance when API keys are
configured. Every quote carries a status (LIVE, DELAYED, STALE, UNAVAILABLE, SIMULATED); only fresh LIVE or
DELAYED quotes can back an order. See [DATA.md](DATA.md) for the fields that are still placeholders.

---

## Quick start

Prerequisites: JDK 25, Maven 3.9+, and Node 22 only if you rebuild the frontend.

```bash
# Build and test (336 tests, JaCoCo coverage gate)
mvn clean verify

# Configuration: copy config.example.yaml to config.yaml, and put secrets in .env (never committed):
#   API_SECRET=<at least 32 characters>
#   OPERATOR_PASSWORD=<at least 12 characters>
#   FINNHUB_KEY=...  POLYGON_API_KEY=...  (any provider you have)

# Check the configuration and environment without starting anything (exit 0 = valid)
java -jar target/options-pricing-engine-1.0.0-SNAPSHOT.jar --check-config

# Run the engine and the dashboard (http://127.0.0.1:8080, WebSocket on port 8081)
java --add-modules jdk.incubator.vector -jar target/options-pricing-engine-1.0.0-SNAPSHOT.jar

# Optional: refresh market_data.csv from a real provider (exits with status 2 if none answers)
python fetch_real_api_data.py
```

Sign in with `OPERATOR_PASSWORD`. The paper-trading panel shows positions and every order with its fill or
rejection reason. `run_all.ps1` runs the same checks as CI locally.

---

## Documentation

- [EXECUTION.md](EXECUTION.md): the order path, paper fills, the API, and what the gateway simulations are
- [RISK.md](RISK.md): every gate an order passes, Greeks, alerts and halt, VaR/ES, the margin approximation
- [DATA.md](DATA.md): spot providers and statuses, option chains, rates, dividends, known placeholders
- [STRATEGY.md](STRATEGY.md): the implemented strategy and the research backlog
- [BACKTEST.md](BACKTEST.md): what the backtester does and its simplifications
- [RESEARCH.md](RESEARCH.md): the mathematics of each model and its limits
- [DESIGN.md](DESIGN.md): the dashboard, panel by panel
- [INFRA.md](INFRA.md): build, configuration, environment variables, process layout, CI
- [HFT_ARCHITECTURE.md](HFT_ARCHITECTURE.md): design notes for the memory-mapped IPC and vectorised maths
- [ROADMAP.md](ROADMAP.md): done, this week, later
- [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_STYLE.md](CODE_STYLE.md), [AGENTS.md](AGENTS.md): working agreements
