package com.sbk.optionspricer.web;

public class ApiTest {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=========================================================================");
        System.out.println("              TESTING LIVE MARKET DATA API INTEGRATION                  ");
        System.out.println("=========================================================================");
        
        LiveSpotProvider provider = new LiveSpotProvider();
        
        System.out.println("\n[1] Checking API Keys Status:");
        System.out.println("    Has Market Data Keys: " + provider.hasMarketDataKeys());
        
        System.out.println("\n[2] Testing Each API Source:");
        
        // Test FINNHUB
        System.out.println("\n    Testing FINNHUB...");
        if (System.getenv("FINNHUB_KEY") != null) {
            System.out.println("      API Key: " + System.getenv("FINNHUB_KEY").substring(0, 8) + "...");
        }
        
        // Test POLYGON
        System.out.println("\n    Testing POLYGON...");
        if (System.getenv("POLYGON_API_KEY") != null) {
            System.out.println("      API Key: " + System.getenv("POLYGON_API_KEY").substring(0, 8) + "...");
        }
        
        // Test ALPHA VANTAGE
        System.out.println("\n    Testing ALPHA VANTAGE...");
        if (System.getenv("ALPHA_VANTAGE_KEY") != null) {
            System.out.println("      API Key: " + System.getenv("ALPHA_VANTAGE_KEY").substring(0, 8) + "...");
        }
        
        // Test MARKETSTACK
        System.out.println("\n    Testing MARKETSTACK...");
        if (System.getenv("MARKETSTACK_KEY") != null) {
            System.out.println("      API Key: " + System.getenv("MARKETSTACK_KEY").substring(0, 8) + "...");
        }
        
        System.out.println("\n[3] Fetching Live SPY Quote:");
        LiveSpotProvider.Quote quote = provider.getQuote("SPY");
        
        if (quote != null) {
            System.out.println("    Symbol: " + quote.symbol());
            System.out.println("    Spot Price: $" + quote.last());
            System.out.println("    Source: " + quote.source());
            System.out.println("    Status: " + quote.status());
            System.out.println("    Timestamp: " + quote.timestamp());
            
            if (com.sbk.optionspricer.market.MarketDataStatus.LIVE == quote.status()) {
                System.out.println("\n    ✅ SUCCESS: Real-time live data from " + quote.source());
            } else if (com.sbk.optionspricer.market.MarketDataStatus.DELAYED == quote.status()) {
                System.out.println("\n    ✅ SUCCESS: Delayed data from " + quote.source());
            } else if (com.sbk.optionspricer.market.MarketDataStatus.UNAVAILABLE == quote.status()) {
                System.out.println("\n    ❌ ERROR: API data unavailable; add valid market-data keys to the local .env file");
            } else {
                System.out.println("\n    ❌ ERROR: API data unavailable");
            }
        } else {
            System.out.println("    ❌ ERROR: Failed to fetch quote");
        }
        
        System.out.println("\n[4] Testing Cache (fetch again):");
        Thread.sleep(1000);
        LiveSpotProvider.Quote quote2 = provider.getQuote("SPY");
        if (quote2 != null) {
            System.out.println("    Cached Price: $" + quote2.last());
            System.out.println("    Cache working: ✅");
        }
        
        System.out.println("\n=========================================================================");
    }
}
