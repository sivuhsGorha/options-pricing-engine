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
| `LIVE` | real-time quote with a source timestamp | yes, if under 30 s old |
| `DELAYED` | delayed or end-of-day price (Polygon, MarketStack, Alpha Vantage outside hours) | yes, if under 30 s old |
| `STALE` | a LIVE/DELAYED quote whose own timestamp is older than 30 s | never |
| `UNAVAILABLE` | no provider answered; there is **no price** (the UI shows `--`) | never |
| `SIMULATED` | no API key configured; a constructed quote around the configured spot | only with `ALLOW_SIMULATED_DATA=true` |

`market/LiveMarketSnapshotAdapter` turns a quote into the `MarketSnapshot` the order manager checks.
Freshness is judged on the price's own timestamp, not the provider's label.

### Known limits of the spot feed (fix candidates)
- **Volume** is a placeholder of 2000 for every provider except Polygon. The liquidity check in the pre-trade
  filter therefore sees a fabricated volume for most quotes.
- **Bid/ask** for providers that return only a last price are set to last +/- 1 cent. The spread check sees a
  nominal spread, not the market's.
- Free tiers: Finnhub and Alpha Vantage are rate limited; Polygon's free plan returns previous close only.

---

## 2. Option chains

`UnifiedQuantEngine` loads an option chain through `CompositeOptionChainProvider`: `YahooFinanceOptionChain`
(unofficial endpoint, best effort) for sources listed in `market_data.sources`, then always
`SyntheticOptionChainProvider` as the fallback. **If Yahoo fails, the chain is synthetic**: Black-Scholes prices
around `market_data.spot` (default 100) at `volatility.default_volatility`. The startup log line
`[LIVE MARKET DATA] SPY spot=100.0 | strikes=14` is that fallback. Quotes that are inverted to implied
volatility use `VolatilitySurfaceCalibrator`, which returns no value rather than a made-up one when a price
cannot be inverted.

The dashboard's 3D surface does **not** use the chain: `/api/surface3d` evaluates SSVI or SABR with fixed
parameters at spot 100 (see [DESIGN.md](DESIGN.md)).

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
