package com.sbk.optionspricer.gateways;

import com.sbk.optionspricer.BlackScholesPricer;
import com.sbk.optionspricer.Greeks;
import com.sbk.optionspricer.ImpliedVolatilitySolver;
import com.sbk.optionspricer.OptionParameters;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.core.MarketDataRingBuffer;
import com.sbk.optionspricer.core.OrderBookTick;

public class RealtimePricingIntegration {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Starting Realtime Pricing Engine Integration ===");
        
        // 1. Initialize the Zero-Allocation Ring Buffer
        int capacity = 1024 * 8; // 8192 capacity
        MarketDataRingBuffer ringBuffer = new MarketDataRingBuffer(capacity);
        
        // 2. Initialize the Binary Decoder
        EobiDecoder decoder = new EobiDecoder(ringBuffer);
        
        // 3. Initialize the Simulated Replay Engine using our CSV data
        HistoricalReplayEngine replay = new HistoricalReplayEngine(decoder, "market_data.csv");
        
        // Hardcoded assumptions for the synthetic SPY data environment
        double spot = 500.0;
        double riskFreeRate = 0.05;
        double timeToExpiry = 30.0 / 365.0;
        
        // Pre-allocate the greeks array once outside the loop to prevent GC allocations
        double[] scratchGreeks = new double[5];

        // 4. Start the Strategy Consumer Thread
        Thread strategyThread = new Thread(() -> {
            int received = 0;
            // The python script generates 42 rows (21 calls, 21 puts). We can loop until we see all 42.
            while (received < 42) {
                OrderBookTick tick = ringBuffer.poll();
                if (tick != null) {
                    received++;
                    
                    int id = tick.getInstrumentId();
                    boolean isCall = (id % 2 == 0);
                    // Strike recovery from id formatting in python script
                    double strike = isCall ? (id / 1000.0) : ((id - 1) / 1000.0);
                    OptionType type = isCall ? OptionType.CALL : OptionType.PUT;
                    
                    double midPrice = tick.getMidPrice();
                    
                    // Solve for IV directly using primitive arguments (dividend yield = 0.0)
                    java.util.OptionalDouble impliedVolOpt = ImpliedVolatilitySolver.solve(
                            type, spot, strike, timeToExpiry, riskFreeRate, 0.0, midPrice, scratchGreeks
                    );
                    
                    if (impliedVolOpt.isEmpty()) {
                        System.out.printf(java.util.Locale.ROOT, "Processed %-4s Strike: %.1f | Mid: %5.2f | IV: FAILED TO SOLVE%n",
                            type, strike, midPrice);
                    } else {
                        double impliedVol = impliedVolOpt.getAsDouble();
                        // Calculate Greeks directly into the pre-allocated scratch buffer
                        BlackScholesPricer.greeks(
                                type, spot, strike, timeToExpiry, riskFreeRate, impliedVol, 0.0, scratchGreeks
                        );
                        
                        double delta = scratchGreeks[0];
                        double gamma = scratchGreeks[1];
                        double vega = scratchGreeks[2];
                        
                        System.out.printf(java.util.Locale.ROOT, "Processed %-4s Strike: %.1f | Mid: %5.2f | IV: %5.2f%% | Delta: %6.3f | Gamma: %6.4f | Vega: %5.3f%n",
                            type, strike, midPrice, impliedVol * 100, delta, gamma, vega / 100);
                    }

                } else {
                    Thread.onSpinWait();
                }
            }
            System.out.println("Pricing Engine successfully processed all messages.");
        });
        
        strategyThread.start();
        
        // 5. Fire the replay engine
        replay.start();
        
        // Wait for completion
        strategyThread.join();
        ringBuffer.shutdown();
        
        System.out.println("Integration Test Complete.");
    }
}
