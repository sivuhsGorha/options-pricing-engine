package com.sbk.optionspricer.gateways;

import com.sbk.optionspricer.core.MarketDataRingBuffer;
import com.sbk.optionspricer.core.OrderBookTick;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * High-performance binary decoder for simulated Eurex EOBI (Enhanced Order Book Interface) packets.
 * Decodes raw network byte streams directly into off-heap OrderBookTicks without creating any objects.
 */
public class EobiDecoder {
    
    // Scale factor used by exchanges to send prices as integers instead of floats
    private static final double PRICE_SCALE = 1e-4;
    private final MarketDataRingBuffer ringBuffer;

    public EobiDecoder(MarketDataRingBuffer ringBuffer) {
        this.ringBuffer = ringBuffer;
    }

    /**
     * Decodes a raw binary packet and publishes it to the Disruptor ring buffer.
     * 
     * Simulated EOBI Schema (37 bytes):
     * [0]     MsgType (1 byte)
     * [1-8]   Timestamp (8 bytes, Little Endian)
     * [9-12]  InstrumentID (4 bytes, Little Endian)
     * [13-16] BidSize (4 bytes, Little Endian)
     * [17-24] BidPrice (8 bytes, Little Endian, Scaled x 10,000)
     * [25-28] AskSize (4 bytes, Little Endian)
     * [29-36] AskPrice (8 bytes, Little Endian, Scaled x 10,000)
     */
    public void onMessage(byte[] packet) {
        // Fast-fail if not an OrderBook snapshot (MsgType = 1)
        if (packet[0] != 1) return;
        
        // We use ByteBuffer.wrap for convenience here, but in production, 
        // we'd read directly from the NIC's receive buffer memory segment.
        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        
        // 1. Claim next slot in the ring buffer
        OrderBookTick tick = ringBuffer.claim();
        
        // 2. Decode bytes directly into the off-heap struct
        tick.setTimestamp(buffer.getLong(1));
        tick.setInstrumentId(buffer.getInt(9));
        tick.setBidSize(buffer.getInt(13));
        
        // Convert integer scaled price back to standard double
        long rawBidPrice = buffer.getLong(17);
        tick.setBidPrice(rawBidPrice * PRICE_SCALE);
        
        tick.setAskSize(buffer.getInt(25));
        
        long rawAskPrice = buffer.getLong(29);
        tick.setAskPrice(rawAskPrice * PRICE_SCALE);
        
        // 3. Publish to consumer threads
        ringBuffer.commit();
    }
}
