package com.sbk.optionspricer.gateways;

import com.sbk.optionspricer.core.MarketDataRingBuffer;
import com.sbk.optionspricer.core.OrderBookTick;

public class GatewayTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Phase 3: Exchange Gateway Integration Test ===");
        
        // 1. Initialize the Zero-Allocation Ring Buffer (from Phase 2)
        int capacity = 1024 * 8; // 8192 capacity
        MarketDataRingBuffer ringBuffer = new MarketDataRingBuffer(capacity);
        
        // 2. Initialize the Binary Decoder
        EobiDecoder decoder = new EobiDecoder(ringBuffer);
        
        // 3. Initialize the Simulated Replay Engine using our CSV data
        HistoricalReplayEngine replay = new HistoricalReplayEngine(decoder, "market_data.csv");
        
        // 4. Start the Strategy Consumer Thread
        Thread strategyThread = new Thread(() -> {
            int received = 0;
            // We know the generator produced exactly 42 options (21 calls, 21 puts)
            while (received < 42) {
                OrderBookTick tick = ringBuffer.poll();
                if (tick != null) {
                    received++;
                    if (received <= 5) { // just print the first 5 to verify
                        System.out.printf("Strategy Received Tick -> ID: %d, Bid: %.2f (x%d), Ask: %.2f (x%d)%n",
                                tick.getInstrumentId(), 
                                tick.getBidPrice(), tick.getBidSize(),
                                tick.getAskPrice(), tick.getAskSize());
                    }
                } else {
                    Thread.onSpinWait();
                }
            }
            System.out.println("Strategy Thread successfully consumed all messages from the ring buffer.");
        });
        
        strategyThread.start();
        
        // 5. Fire the replay engine
        replay.start();
        
        // Wait for completion
        strategyThread.join();
        ringBuffer.shutdown();
        
        System.out.println("Pipeline Test Complete.");
    }
}
