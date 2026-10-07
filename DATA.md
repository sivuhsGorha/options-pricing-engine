# Market Data (DATA.md)

Where every number on screen comes from, how fresh it is, and which numbers are not market data at all.

---

## 1. Spot prices

`web/LiveSpotProvider` queries, in order, Finnhub (real-time quote), Polygon (previous close), Alpha Vantage
(global quote) and MarketStack (end-of-day), for the configured symbol. Each provider has its own gate: a
failure or rejection suspends it for a back-off period (5 s for a timeout, 60 s for rate limiting, 15 min for
rejected credentials), and a request budget stops the app burning a free-tier quota. Refreshes run on a
background thread; a request never blocks startup or an engine tick.

Each quote carries a **status** (`market/MarketDataStatus`):

| Status | Meaning | Tradable (default policy) |
| :--- | :--- | :--- |
| `LIVE` | real-time quote with a source timestamp | yes, if under 120 s old (it turns STALE after 30 s anyway) |
| `DELAYED` | delayed price (Finnhub's free quote, Alpha Vantage, Marketstack end-of-day) | yes, if under 120 s old |
| `STALE` | a LIVE quote older than 30 s, or a DELAYED quote older than 120 s, by its own timestamp | never |
| `UNAVAILABLE` | no provider answered; there is **no price** (the UI shows `--`) | never |
| `SIMULATED` | no API key configured; a constructed quote around the configured spot | only with `ALLOW_SIMULATED_DATA=true` |

`market/LiveMarketSnapshotAdapter` turns a quote into the `MarketSnapshot` the order manager checks.
Freshness is judged on the price's own timestamp, not the provider's label.

### What each provider supplies
| Provider | Price | Book (bid/ask) | Volume | Status |
| :--- | :--- | :--- | :--- | :--- |
| Finnhub `/quote` | last | none | none | DELAYED |
| Polygon snapshot | last trade (or midpoint) | yes | minute bar, else day, else none | LIVE |
| Alpha Vantage `GLOBAL_QUOTE` | last | none | daily | DELAYED |
| Marketstack `eod/latest` | close | none | daily | DELAYED |

A field a provider does not supply is **unknown** (`NaN` book, `MarketSnapshot.VOLUME_UNKNOWN`), shown as
`null` on `/api/spot` and absent from the header badge. The liquidity gate judges spread only when a book
exists and volume only when it is known. Paper fills without a book execute against the order's own price.
Free tiers: Finnhub and Alpha Vantage are rate limited; Polygon's free plan returns previous close only.

---

## 2. Option chains

Chains come through `CompositeOptionChainProvider`, which tries the sources in `market_data.sources` in order
and always ends with `SyntheticOptionChainProvider`:

| Source | Class | What it is |
| :--- | :--- | :--- |
| `cboe` (default) | `CboeOptionChain` | Cboe's public delayed-quotes JSON: every listed expiry with bid, ask, implied volatility, volume and open interest, 15-minute delayed, no key. One request per symbol, cached 5 minutes. Labelled `CBOE_DELAYED`; it is market data |
| `yahoo_finance` | `YahooFinanceOptionChain` | Yahoo's unofficial endpoint, which now answers `401 Invalid Crumb` to plain requests; kept but not default |
| (always last) | `SyntheticOptionChainProvider` | Black-Scholes prices around `market_data.spot` at `volatility.default_volatility`. Labelled `SYNTHETIC`; **not** market data, so a surface fitted to it is a `DEMO` |

Each chain records which provider produced it and why any earlier provider failed, so the dashboard can say
"DEMO" and, in the badge tooltip, "Provider CBOE_DELAYED failed: Cboe returned HTTP 503". Quotes are inverted
to implied volatility by `VolatilitySurfaceCalibrator`, which returns no value rather than a made-up one when a
price cannot be inverted.

**Surface calibration** (`core/VolatilitySurfaceService`, `volatility/SurfaceFitter`): on its own thread,
every `market_data.refresh_interval_seconds`, the service asks the provider for its listed expiries (Cboe's
contract list; the next eight monthly third Fridays for providers without a listing), picks the dates nearest
to 1, 2, 3 and 6 months, loads those chains, inverts the out-of-the-money two-sided quotes to implied volatility,
and fits SSVI (global eta, gamma, rho with per-expiry ATM variance from the data), raw SVI (a, b, rho, m, sigma
per expiry) and SABR (alpha, rho, nu per expiry, beta 0.5) by Nelder-Mead least squares. The result carries the provider name, whether it is market data,
the quotes used and skipped, the RMSE, the parameters, whether SSVI's closed-form no-arbitrage conditions hold,
and warnings. `/api/surface3d` returns it, or the calibration status while nothing is fitted. If the chain came
from the synthetic fallback the surface is labelled `DEMO`; nothing on the startup path waits for this.

---

## 3. Rates and day count

- `TimeConventions`: one day count, ACT/365 Fixed, used everywhere a date becomes a time.
- `rates/YieldCurve`: discount factors with log-linear interpolation (piecewise-constant forwards), flat
  extrapolation of the zero rate before the first pillar and of the forward after the last.
- `rates/OisCurveBootstrapper`: bootstraps par swap or par yield quotes with a real payment schedule, solving
  each pillar numerically so interpolated payment dates are consistent.
- `rates/FredYieldCurve`: US Treasury constant-maturity **par yields** (semiannual bond-equivalent),
  bootstrapped as coupon bonds; needs `FRED_API_KEY`.
- `rates/EsterRateProvider`: **only the overnight €STR anchor is market data.** The rest of the curve is a
  generated shape, `isMarketData()` is false, and it must not be used for anything that matters.

The engine itself prices with the flat `market_data.risk_free_rate` from config; the curve classes are
available but not wired into the live pricing path.

---

## 4. Dividends

`data/YahooDividendProvider` fetches dividend history from an unofficial Yahoo endpoint (cached for hours,
symbol validated, URL never logged). `data/DividendForecaster` projects each past dividend one year forward
with the same amount: a naive model that treats special dividends as recurring. The PDE pricer
(`models/pde/DiscreteDividendPricer`) accepts discrete cash dividends at exact ex-dates; the live engine uses
the continuous `market_data.dividend_yield`.

---

## 5. The Python fetch script

[`fetch_real_api_data.py`](fetch_real_api_data.py) fetches the current spot from the same four providers
(keys from `.env` or the environment, 5 s timeouts, credentials never printed) and writes a **synthetic**
Black-Scholes call/put chain around it to [`market_data.csv`](market_data.csv) for the replay engine. Only
the spot is real. If no provider answers it exits with status 2 and leaves the file untouched; `--synthetic
--spot N` generates fully synthetic data on purpose. The file is replaced atomically. Tests:
`python -m unittest discover -s scripts -p "test_*.py"`.

---

## 6. Not implemented

Exchange multicast feeds (EOBI, Optiq MDG, GTP, ITCH), L3 order book reconstruction, continuous surface
calibration to live chains, multi-currency curve construction (SONIA, SARON, STIBOR), and a corporate-actions
feed. The `gateways/` package contains simulations of a binary feed decoder only.
