package com.sbk.optionspricer.gateways;

import com.sbk.optionspricer.core.MarketDataRingBuffer;
import com.sbk.optionspricer.core.OrderBookTick;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;

/**
 * High-performance binary decoder for simulated Eurex EOBI (Enhanced Order Book Interface) packets.
 * Decodes raw network byte streams directly into off-heap OrderBookTicks without creating any objects.
 */
public class EobiDecoder {
    
    // Scale factor used by exchanges to send prices as integers instead of floats
    private static final double PRICE_SCALE = 1e-4;
    private static final int PACKET_LENGTH = 37;
    private final MarketDataRingBuffer ringBuffer;
    private long lastTimestamp = -1;
    private long lastLogTime = 0;

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
        if (packet == null || packet.length < PACKET_LENGTH) {
            System.err.println("[EOBI] Dropped undersized packet. Hex: " + 
                (packet == null ? "null" : HexFormat.of().formatHex(packet)));
            return;
        }
        
        // Fast-fail if not an OrderBook snapshot (MsgType = 1) with explicit masking
        if ((packet[0] & 0xFF) != 1) return;
        
        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        
        long timestamp = buffer.getLong(1);
        if (timestamp <= lastTimestamp) {
            System.err.println("[EOBI] Dropped out-of-order sequence: " + timestamp + " <= " + lastTimestamp);
            return;
        }
        
        int instrumentId = buffer.getInt(9);
        int bidSize = buffer.getInt(13);
        long rawBidPrice = buffer.getLong(17);
        int askSize = buffer.getInt(25);
        long rawAskPrice = buffer.getLong(29);

        double bidPrice = rawBidPrice * PRICE_SCALE;
        double askPrice = rawAskPrice * PRICE_SCALE;

        long nowNs = System.currentTimeMillis();
        // Value bounds checks
        if (bidSize < 0 || askSize < 0 || bidPrice < 0 || askPrice < 0 || 
            Double.isNaN(bidPrice) || Double.isNaN(askPrice) || 
            Double.isInfinite(bidPrice) || Double.isInfinite(askPrice) ||
            timestamp < 0 || timestamp > nowNs + 86400000L) {
            
            long now = System.currentTimeMillis();
            if (now - lastLogTime > 1000) {
                System.err.println("[EOBI] Dropped corrupt payload values. Hex: " + HexFormat.of().formatHex(packet));
                lastLogTime = now;
            }
            return;
        }

        lastTimestamp = timestamp;

        // 1. Claim next slot in the ring buffer
        OrderBookTick tick = ringBuffer.claim();
        
        // 2. Decode bytes directly into the off-heap struct
        tick.setTimestamp(timestamp);
        tick.setInstrumentId(instrumentId);
        tick.setBidSize(bidSize);
        tick.setBidPrice(bidPrice);
        tick.setAskSize(askSize);
        tick.setAskPrice(askPrice);
        
        // 3. Publish to consumer threads
        ringBuffer.commit();
    }
}
