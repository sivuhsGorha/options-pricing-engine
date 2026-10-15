# Dashboard Design (DESIGN.md)

The dashboard is a single-page React app (`web-react/`, built into `web/`) served by the Java process. It is
styled after a terminal: dark background, amber and cyan accents, monospaced numbers, 1 px dividers.

---

## 1. Screens and panels

**Login.** Operator password only (`OPERATOR_PASSWORD`). Wrong attempts are rate limited server-side and the
form says so. The session is a secure, same-site cookie; SIGN OUT ends it.

**Data banner** (top). One of four states, driven by `/api/spot`:
`LIVE MARKET DATA` (green), `DELAYED/EOD` (green), `STALE` (amber, last quote older than 30 s),
`MARKET DATA FEED UNAVAILABLE` (red), or `SIMULATED DATA` (red) when no key is configured.

**Header.** Command bar (`BLACKTHORN STERLING > <command> <GO>`), function keys F1-F5 and SIGN OUT, the spot badge
(symbol, price, source, status, time), and one badge per market-data provider with its status from
`/api/health`.

**Left: Portfolio Risk Matrix.** Net delta, gamma, vega (execution book first, engine state as fallback), theta
and rho from the valuation service with the time and spot they were computed at,
scenario margin, execution-book notional, the margin optimizer's suggested hedge and reduction, and the
terminal event log (last 8 lines). Under the P&L figures, a **WHY P&L MOVED** block splits P&L since the engine
started into delta, gamma/theta, vega and OTHER (the spread paid on fills and anything the Greeks do not explain),
from the `attribution` block of `/api/pnl`. While the book holds only shares its caption says that only delta can
explain anything; the figures are in memory and restart with the engine.

**Footer.** The market ribbon, then **CHAIN RECORDER**: `ON · N files · M skipped (feed unchanged) · last <time>`
from `components.recorder` of `/api/health` (or `OFF`, or `UNAVAILABLE`). It is informational and never changes the
overall health status; overnight the feed does not change, so a recorder that has not written is expected then.

**Centre: Volatility surface.** A 3D surface plus smile and term-structure charts (Plotly). Buttons switch
between SSVI (one global fit with closed-form no-arbitrage conditions), SVI (Gatheral's raw form, five
parameters per expiry, the closest fit) and SABR 2002 (three parameters per expiry). The surface is **fitted to the option chain** by
`core/VolatilitySurfaceService` in the background, and the badge in the panel header states its provenance:
`FIT · <provider> · N quotes · RMSE x vol pts` (green) when the chain is market data, `DEMO · SYNTHETIC` (amber)
when the chain is the synthetic fallback, or the calibration status (`LOADING`, `FAILED: reason`) while no fit
exists. The quotes the surface was fitted to are overlaid as markers on the 3D plot and the smile (thinned to about
forty per expiry, inside the drawn strike band), so the fit can be judged by eye. Three models fitted to the
same quotes look nearly identical as surfaces, so the smile panel has a second row: each model's error against
the quotes at the shortest expiry, in vol points, which is where a global three-parameter fit and a per-slice
five-parameter fit visibly differ. Warnings (too few quotes, calendar arbitrage in the data, iteration cap) are in
the badge tooltip. Each chart has EXPAND / SHRINK.

**Right: Paper Trading.** Trading status (ACTIVE with a HALT button, or HALTED with the reason and a RESUME
button), the strategy row (ON/OFF switch, mode, and in vol-spread mode the strategy's latest decision in its own words,
e.g. `HOLD 2026-11-20 775: market 18.2% vs ref 17.4% (edge +0.80 pts) - edge within the 1.00-point band`), the
valuation line (time, spot and its source, unrealised and realised P&L), the position table (contract such as
`SPY 20 Nov 26 780 C`, quantity, multiplier, mark with its source in the tooltip, unrealised P&L, notional at the last fill price, not at the mark) and the last 25 orders (time, id, quantity, fill price, `FILLED (<data
status>)` or `REJECTED: <reason>`), from `/api/positions`, `/api/control` and `/api/execution`. The buttons
call POST endpoints that require the session cookie and the page's own Origin (see EXECUTION.md); every
action is written to the event log with its outcome.

**Footer ribbon.** SPY price and status, SOURCE, and GATEWAY, which names the transport: `PAPER` (the
in-process simulator) or `ALPACA` (the Alpaca paper account). The paper-trading panel's TRANSPORT badge adds
the reconciliation state, and a line under the strategy row shows when the book was last reconciled or which
positions differ.

---

## 2. Commands

| Command / key | Effect |
| :--- | :--- |
| `HELP` / F1 | lists the shortcuts in the event log |
| `TICK` / F2 | describes the paper-trading panel |
| `VOLS` / F3 | un-expands the charts |
| `RISK`, `MARGIN` / F4, F5 | logs a note; the risk panel is always visible |
| `HALT`, `RESUME` | trip or clear the trading halt (same as the panel buttons) |
| `STRATEGY ON`, `STRATEGY OFF` | switch order generation on or off |
| `SSVI`, `SABR` | switch the surface model |
| anything else | `UNKNOWN FUNCTION` |

---

## 3. Refresh rates

Risk every 1 s; spot, positions and orders every 2 s; surface and provider health every 5 s. A failed request
is logged (risk: "DESYNC") and never parsed as data. There is also a WebSocket feed on the next port up
(`web/WebSocketDashboardServer`); the React UI currently polls the HTTP API.

---

## 4. Palette

| Token | Hex | Use |
| :--- | :--- | :--- |
| background | `#05070A` | page |
| panel | `#0C0F14` | panels |
| border | `#1C232D` | 1 px dividers |
| amber | `#FF9900` | prompt, hotkeys, key metrics, focus |
| cyan | `#00E5FF` | headers, tickers |
| text | `#E0E6ED` | numbers |
| muted | `#5C6B73` | column headers, timestamps |
| up / ok | `#00E676` | live data, filled orders, active trading |
| down / alert | `#FF3D00` | unavailable data, rejected orders, halt |

Fonts: JetBrains Mono / Roboto Mono for numbers, Inter for headers.

### Identity

The system is called **Blackthorn Sterling** (options pricing, volatility surface and paper trading). Until
2026-10-15 the code called it AURA-OPT. The names that are technical, and were left alone, are the repository
(`options-pricing-engine`), the jar and the Docker image and volume; `web-react/` is only the folder the dashboard's source
lives in. The mark is a **V**, the payoff of a long straddle, which is lowest at the strike, with arcs spreading from its
apex: the volatility smile. It uses the palette above (the V runs amber to cyan on `#0A1017`).

| File | Use |
| :--- | :--- |
| `web-react/public/favicon.svg` | the mark: browser tab, login screen, header (`/favicon.svg`) |
| `docs/logo.svg` | the lockup (mark, wordmark in two lines, tagline) at the top of the README |

The package is `blackthorn-sterling-dashboard` and the tab reads `Blackthorn Sterling · options pricing & paper trading`;
`src/brand.test.js` fails if the template name, the template icon, either logo file or the previous name comes back.
---

## 5. Code layout

`web-react/src/App.jsx` holds state and polling; `components/` holds `LoginForm`, `DataBanner`, `RiskPanel`,
`ChartPanel`, `ExecutionPanel`; `lib/` holds `api.js` (same-origin fetch under `/api`), `format.js` and
`surfaceCharts.js` (Plotly layouts) and `surfaceLabel.js` (the provenance badge). Tests sit next to the code
as `*.test.js(x)` (Vitest, React Testing Library, jsdom); `npm test` runs them. `npm run lint` fails on
warnings; CI runs lint, tests and build.
