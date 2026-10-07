package com.sbk.optionspricer.gateways;

import com.sbk.optionspricer.core.MarketDataRingBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SIMULATION: mock feed, not a multicast UDP handler. It generates random packets in a made-up
 * EOBI-like layout and passes them to the EobiDecoder; no network socket is opened and the
 * prices are random numbers, not market data.
 */
public class EobiMarketDataHandler {

    private final EobiDecoder decoder;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread mockFeedThread;

    public EobiMarketDataHandler(MarketDataRingBuffer ringBuffer) {
        this.decoder = new EobiDecoder(ringBuffer);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            mockFeedThread = new Thread(this::mockMulticastFeed, "EOBI-Mock-Feed");
            mockFeedThread.start();
        }
    }

    public void stop() {
        running.set(false);
        if (mockFeedThread != null) {
            try {
                mockFeedThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void mockMulticastFeed() {
        long sequence = 0;
        
        while (running.get()) {
            try {
                // Generate a mock 37-byte EOBI packet
                byte[] packet = new byte[37];
                ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
                
                buffer.put(0, (byte) 1); // MsgType = 1 (OrderBook Snapshot)
                buffer.putLong(1, sequence++); // Sequence/Timestamp
                buffer.putInt(9, 1001); // InstrumentID
                
                // Randomly fluctuate sizes and prices for simulation
                int bidSize = 100 + (int)(Math.random() * 50);
                long bidPrice = 4120000L + (long)(Math.random() * 10000); // Scaled by 1e-4 -> ~412.0
                
                int askSize = 100 + (int)(Math.random() * 50);
                long askPrice = bidPrice + 500L; // 0.05 spread
                
                buffer.putInt(13, bidSize);
                buffer.putLong(17, bidPrice);
                buffer.putInt(25, askSize);
                buffer.putLong(29, askPrice);
                
                decoder.onMessage(packet);
                
                // Throttle simulation to roughly 1000 updates per second
                Thread.sleep(1); 
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Error in EOBI mock feed: " + e.getMessage());
            }
        }
    }
}
