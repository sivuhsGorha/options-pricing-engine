import csv
import time
import urllib.request
import json
import os

POLYGON_API_KEY = os.getenv("POLYGON_API_KEY")
ALPHA_VANTAGE_KEY = os.getenv("ALPHA_VANTAGE_KEY")
FINNHUB_KEY = os.getenv("FINNHUB_KEY")
MARKETSTACK_KEY = os.getenv("MARKETSTACK_KEY")

def fetch_finnhub_quote(symbol="SPY"):
    url = f"https://finnhub.io/api/v1/quote?symbol={symbol}"
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0', 'X-Finnhub-Token': FINNHUB_KEY})
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            if 'c' in data and data['c'] > 0:
                print(f"[FINNHUB API] Fetched live quote for {symbol}: Current=${data['c']}, High=${data['h']}, Low=${data['l']}")
                return data['c']
    except Exception as e:
        print(f"[FINNHUB API] Error fetching quote: {e}")
    return None

def fetch_polygon_previous_close(symbol="SPY"):
    url = f"https://api.polygon.io/v2/aggs/ticker/{symbol}/prev?adjusted=true"
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0', 'Authorization': f'Bearer {POLYGON_API_KEY}'})
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            if 'results' in data and len(data['results']) > 0:
                close_price = data['results'][0]['c']
                print(f"[POLYGON API] Fetched prev close for {symbol}: ${close_price}")
                return close_price
    except Exception as e:
        print(f"[POLYGON API] Error fetching prev close: {e}")
    return None

def fetch_alpha_vantage_quote(symbol="SPY"):
    url = f"https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol={symbol}&apikey={ALPHA_VANTAGE_KEY}"
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            if 'Global Quote' in data and '05. price' in data['Global Quote']:
                price = float(data['Global Quote']['05. price'])
                if price > 0:
                    print(f"[ALPHA VANTAGE API] Fetched live price for {symbol}: ${price:.2f}")
                    return price
    except Exception as e:
        print(f"[ALPHA VANTAGE API] Error fetching quote: {e}")
    return None

def fetch_marketstack_eod(symbol="SPY"):
    url = f"https://api.marketstack.com/v1/eod/latest?access_key={MARKETSTACK_KEY}&symbols={symbol}"
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            if 'data' in data and len(data['data']) > 0 and 'close' in data['data'][0]:
                close_price = data['data'][0]['close']
                print(f"[MARKETSTACK API] Fetched EOD close for {symbol}: ${close_price}")
                return close_price
    except Exception as e:
        print(f"[MARKETSTACK API] Error fetching EOD data: {e}")
    return None

def update_market_data_csv(spot_price):
    csv_file = 'market_data.csv'
    spot = spot_price if spot_price else 500.0
    r = 0.05
    t = 30.0 / 365.0
    
    print(f"[MARKET DATA PIPELINE] Regenerating option chain around live spot ${spot:.2f}...")
    
    import math
    def norm_cdf(x):
        return (1.0 + math.erf(x / math.sqrt(2.0))) / 2.0

    strikes = range(int(spot - 50), int(spot + 55), 5)
    
    with open(csv_file, 'w', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(['timestamp', 'instrument_id', 'type', 'strike', 'bid_price', 'bid_size', 'ask_price', 'ask_size'])
        
        for strike in strikes:
            moneyness = math.log(strike / spot)
            vol = 0.18 - (0.12 * moneyness)
            
            d1 = (math.log(spot / strike) + (r + (vol ** 2) / 2.0) * t) / (vol * math.sqrt(t))
            d2 = d1 - vol * math.sqrt(t)
            
            call_price = spot * norm_cdf(d1) - strike * math.exp(-r * t) * norm_cdf(d2)
            put_price = strike * math.exp(-r * t) * norm_cdf(-d2) - spot * norm_cdf(-d1)
            
            spread = max(0.05, call_price * 0.02)
            call_bid = max(0.01, call_price - spread/2)
            call_ask = call_price + spread/2
            
            spread_put = max(0.05, put_price * 0.02)
            put_bid = max(0.01, put_price - spread_put/2)
            put_ask = put_price + spread_put/2
            
            writer.writerow([int(time.time() * 1e9), int(strike * 1000), 'CALL', strike, round(call_bid, 2), 100, round(call_ask, 2), 100])
            writer.writerow([int(time.time() * 1e9), int(strike * 1000) + 1, 'PUT', strike, round(put_bid, 2), 100, round(put_ask, 2), 100])
            
    print(f"[MARKET DATA PIPELINE] Successfully updated {csv_file} with live spot ${spot:.2f} option chain!")

if __name__ == '__main__':
    print("=========================================================================")
    print(" MULTI-API MARKET DATA INGESTION PIPELINE (FINNHUB / POLYGON / AV / MS)  ")
    print("=========================================================================")
    
    spot = fetch_finnhub_quote("SPY")
    if not spot:
        spot = fetch_polygon_previous_close("SPY")
    if not spot:
        spot = fetch_alpha_vantage_quote("SPY")
    if not spot:
        spot = fetch_marketstack_eod("SPY")
        
    update_market_data_csv(spot)

