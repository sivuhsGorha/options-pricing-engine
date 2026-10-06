#!/usr/bin/env python3
"""Regenerate market_data.csv around a real SPY spot price.

The option prices in the file are SYNTHETIC (Black-Scholes with a fixed skew): only the spot comes
from a market-data provider. Providers are tried in order (Finnhub, Polygon, Alpha Vantage,
Marketstack); sources without a configured key are skipped. If no provider returns a usable price
the script exits with status 2 and leaves the existing file untouched. It never substitutes an
invented spot: use --synthetic --spot N to ask for fully synthetic data explicitly.

Keys come from the environment or a .env file (the environment wins). Standard library only.
"""
import argparse
import csv
import json
import math
import os
import re
import sys
import tempfile
import time
import urllib.request

TIMEOUT_SECONDS = 5
RATE = 0.05
YEARS_TO_EXPIRY = 30.0 / 365.0
HEADER = ["timestamp", "instrument_id", "type", "strike", "bid_price", "bid_size", "ask_price", "ask_size"]
_CREDENTIAL = re.compile(r"(?i)\b(api_?key|access_key|token|key)=[^&\s\"']+")


def redact(text, secrets=()):
    """Mask credential query parameters and any known secret values before text is printed."""
    text = _CREDENTIAL.sub(lambda m: m.group(0).split("=")[0] + "=***", str(text))
    for secret in secrets:
        if secret:
            text = text.replace(secret, "***")
    return text


def load_keys(env=None, dotenv_path=".env"):
    """Read provider keys from a .env file, overridden by real environment variables."""
    values = {}
    try:
        with open(dotenv_path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, value = line.partition("=")
                key = key.strip()
                if key and " " not in key:
                    values[key] = value.strip().strip('"').strip("'")
    except OSError:
        pass
    values.update({k: v for k, v in (os.environ if env is None else env).items() if v})
    return values


def default_http(url, headers, timeout):
    request = urllib.request.Request(url, headers=dict(headers, **{"User-Agent": "AURA-OPT/1.0"}))
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def _positive(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) and number > 0 else None


# Each provider: (name, key variable, is_live, request builder, price extractor)
def _providers(symbol):
    return [
        ("FINNHUB", "FINNHUB_KEY", True,
         lambda key: (f"https://finnhub.io/api/v1/quote?symbol={symbol}", {"X-Finnhub-Token": key}),
         lambda data: _positive(data.get("c"))),
        ("POLYGON", "POLYGON_API_KEY", False,  # previous close, not a live quote
         lambda key: (f"https://api.polygon.io/v2/aggs/ticker/{symbol}/prev?adjusted=true", {"Authorization": f"Bearer {key}"}),
         lambda data: _positive((data.get("results") or [{}])[0].get("c"))),
        ("ALPHA_VANTAGE", "ALPHA_VANTAGE_KEY", True,
         lambda key: (f"https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol={symbol}&apikey={key}", {}),
         lambda data: _positive((data.get("Global Quote") or {}).get("05. price"))),
        ("MARKETSTACK", "MARKETSTACK_KEY", False,  # end-of-day close
         lambda key: (f"https://api.marketstack.com/v1/eod/latest?access_key={key}&symbols={symbol}", {}),
         lambda data: _positive(((data.get("data") or [{}])[0]).get("close"))),
    ]


def fetch_spot(symbol, keys, http):
    """Return (price, provider, is_live) from the first configured provider that answers, else None."""
    for name, key_name, is_live, build, extract in _providers(symbol):
        key = keys.get(key_name)
        if not key:
            continue  # not configured: skip rather than send a placeholder credential
        try:
            url, headers = build(key)
            price = extract(http(url, headers, TIMEOUT_SECONDS))
        except Exception as error:  # network, HTTP status, malformed JSON...
            print(f"[{name}] unavailable: {redact(error, [key])}")
            continue
        if price is None:
            print(f"[{name}] returned no usable price")
            continue
        print(f"[{name}] {symbol} = ${price:.2f}" + ("" if is_live else "  (previous/end-of-day close, NOT LIVE)"))
        return price, name, is_live
    return None


def _norm_cdf(x):
    return (1.0 + math.erf(x / math.sqrt(2.0))) / 2.0


def build_rows(spot, start_ns):
    """Yield the header then one call row and one put row per strike (timestamps strictly increasing)."""
    yield HEADER
    sequence = 0
    for strike in range(int(spot - 50), int(spot + 55), 5):
        if strike <= 0:
            continue
        vol = 0.18 - 0.12 * math.log(strike / spot)  # equity skew: downside has higher vol
        root_t = math.sqrt(YEARS_TO_EXPIRY)
        d1 = (math.log(spot / strike) + (RATE + vol * vol / 2.0) * YEARS_TO_EXPIRY) / (vol * root_t)
        d2 = d1 - vol * root_t
        discount = strike * math.exp(-RATE * YEARS_TO_EXPIRY)
        call = spot * _norm_cdf(d1) - discount * _norm_cdf(d2)
        put = discount * _norm_cdf(-d2) - spot * _norm_cdf(-d1)
        for kind, price, offset in (("CALL", call, 0), ("PUT", put, 1)):
            spread = max(0.05, price * 0.02)
            bid = max(0.01, price - spread / 2)
            ask = price + spread / 2
            yield [start_ns + sequence, int(strike * 1000) + offset, kind, strike, round(bid, 2), 100, round(ask, 2), 100]
            sequence += 1


def write_csv_atomically(path, rows):
    """Write to a temp file in the same directory, then replace: readers never see a partial file."""
    directory = os.path.dirname(os.path.abspath(path))
    handle, temp_path = tempfile.mkstemp(prefix=".market_data.", suffix=".tmp", dir=directory)
    try:
        with os.fdopen(handle, "w", newline="") as f:
            writer = csv.writer(f)
            for row in rows:
                writer.writerow(row)
            f.flush()
            os.fsync(f.fileno())
        os.replace(temp_path, path)
    except BaseException:
        try:
            os.unlink(temp_path)
        except OSError:
            pass
        raise


def main(argv=None, env=None, http=None):
    parser = argparse.ArgumentParser(description="Regenerate market_data.csv around a real (or explicitly synthetic) spot.")
    parser.add_argument("--symbol", default="SPY")
    parser.add_argument("--output", default="market_data.csv")
    parser.add_argument("--dotenv", default=".env", help="file to read provider keys from (environment variables override it)")
    parser.add_argument("--synthetic", action="store_true", help="skip providers and use --spot (fully synthetic data)")
    parser.add_argument("--spot", type=float, default=500.0, help="spot for --synthetic mode")
    args = parser.parse_args(argv)
    http = http or default_http

    if args.synthetic:
        spot = args.spot
        if not (math.isfinite(spot) and spot > 0):
            print(f"Refusing to write: --spot must be a positive number, got {args.spot}")
            return 2
        print(f"SYNTHETIC MODE: spot ${spot:.2f} was supplied by you, not by a market-data provider.")
    else:
        result = fetch_spot(args.symbol, load_keys(env, args.dotenv), http)
        if result is None:
            print("No market-data provider returned a usable price (check keys and connectivity).")
            print(f"Leaving {args.output} untouched; use --synthetic --spot N to generate synthetic data deliberately.")
            return 2
        spot = result[0]

    print(f"Writing a SYNTHETIC Black-Scholes option chain around spot ${spot:.2f} (option prices are not market data).")
    write_csv_atomically(args.output, build_rows(spot, time.time_ns()))
    print(f"Updated {args.output}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
