import csv
import time
import math

# Simple Black-Scholes to generate realistic option prices
def norm_cdf(x):
    # Approximation of normal CDF
    return (1.0 + math.erf(x / math.sqrt(2.0))) / 2.0

def generate_realistic_data():
    csv_file = 'market_data.csv'
    print("Generating realistic synthetic S&P 500 (SPY) options chain...")
    
    spot = 500.0
    r = 0.05
    t = 30.0 / 365.0 # 30 days to expiry
    
    with open(csv_file, 'w', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(['timestamp', 'instrument_id', 'type', 'strike', 'bid_price', 'bid_size', 'ask_price', 'ask_size'])
        
        # Generate strikes from 450 to 550
        for strike in range(450, 551, 5):
            # Equity skew: downside puts have higher vol than upside calls
            # ATM vol = 15%. Skew slope = -0.1
            moneyness = math.log(strike / spot)
            vol = 0.15 - (0.1 * moneyness)
            
            d1 = (math.log(spot / strike) + (r + (vol ** 2) / 2.0) * t) / (vol * math.sqrt(t))
            d2 = d1 - vol * math.sqrt(t)
            
            # Call price
            call_price = spot * norm_cdf(d1) - strike * math.exp(-r * t) * norm_cdf(d2)
            # Put price
            put_price = strike * math.exp(-r * t) * norm_cdf(-d2) - spot * norm_cdf(-d1)
            
            # Add bid-ask spread (wider for deep OTM)
            spread = max(0.05, call_price * 0.02)
            call_bid = max(0.01, call_price - spread/2)
            call_ask = call_price + spread/2
            
            spread_put = max(0.05, put_price * 0.02)
            put_bid = max(0.01, put_price - spread_put/2)
            put_ask = put_price + spread_put/2
            
            # Write Call
            writer.writerow([
                int(time.time() * 1e9), 
                int(strike * 1000), 'CALL', strike, 
                round(call_bid, 2), 100, round(call_ask, 2), 100
            ])
            # Write Put
            writer.writerow([
                int(time.time() * 1e9), 
                int(strike * 1000) + 1, 'PUT', strike, 
                round(put_bid, 2), 100, round(put_ask, 2), 100
            ])
            
    print(f"Success! Saved realistic options data to {csv_file}")

if __name__ == '__main__':
    generate_realistic_data()
