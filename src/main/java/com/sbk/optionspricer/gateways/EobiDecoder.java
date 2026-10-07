package com.sbk.optionspricer.gateways;

import com.sbk.optionspricer.core.MarketDataRingBuffer;
import com.sbk.optionspricer.core.OrderBookTick;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Binary decoder for a SIMULATED, EOBI-like packet layout (not the real Eurex EOBI schema).
 * Decodes raw network byte streams directly into off-heap OrderBookTicks without creating any objects.
 */
public class EobiDecoder {
    
    // Scale factor used by exchanges to send prices as integers instead of floats
    private static final double PRICE_SCALE = 1e-4;
    private static final int PACKET_LENGTH = 37;
    private final MarketDataRingBuffer ringBuffer;
    private long lastSequence = -1;
    private long lastLogTime = 0;

    public final AtomicLong undersizedPackets = new AtomicLong();
    public final AtomicLong outOfOrderPackets = new AtomicLong();
    public final AtomicLong corruptPayloads = new AtomicLong();
    public final AtomicLong acceptedPackets = new AtomicLong();
    public final AtomicLong gapCount = new AtomicLong();

    private void maybeLogSummary() {
        long now = System.currentTimeMillis();
        if (now - lastLogTime > 1000) {
            System.err.println("[EOBI] Summary: Accepted=" + acceptedPackets.get() +
                " Undersized=" + undersizedPackets.get() +
                " OutOfOrder=" + outOfOrderPackets.get() +
                " Corrupt=" + corruptPayloads.get() +
                " Gaps=" + gapCount.get());
            lastLogTime = now;
        }
    }

    public EobiDecoder(MarketDataRingBuffer ringBuffer) {
        this.ringBuffer = ringBuffer;
    }

    /**
     * Decodes a raw binary packet and publishes it to the SPSC ring buffer.
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
            undersizedPackets.incrementAndGet();
            maybeLogSummary();
            return;
        }
        
        // Fast-fail if not an OrderBook snapshot (MsgType = 1) with explicit masking
        if ((packet[0] & 0xFF) != 1) {
            corruptPayloads.incrementAndGet();
            maybeLogSummary();
            return;
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        
        long sequence = buffer.getLong(1);
        if (sequence < 0) {
            corruptPayloads.incrementAndGet();
            maybeLogSummary();
            return;
        }
        if (lastSequence != -1 && sequence <= lastSequence) {
            outOfOrderPackets.incrementAndGet();
            maybeLogSummary();
            return;
        }
        
        int instrumentId = buffer.getInt(9);
        int bidSize = buffer.getInt(13);
        long rawBidPrice = buffer.getLong(17);
        int askSize = buffer.getInt(25);
        long rawAskPrice = buffer.getLong(29);

        double bidPrice = rawBidPrice * PRICE_SCALE;
        double askPrice = rawAskPrice * PRICE_SCALE;

        // Value bounds checks
        if (bidSize < 0 || askSize < 0 || bidPrice < 0 || askPrice < 0 || 
            Double.isNaN(bidPrice) || Double.isNaN(askPrice) || 
            Double.isInfinite(bidPrice) || Double.isInfinite(askPrice)) {
            
            corruptPayloads.incrementAndGet();
            maybeLogSummary();
            return;
        }

        if (lastSequence != -1 && sequence > lastSequence + 1) {
            gapCount.incrementAndGet();
        }
        lastSequence = sequence;
        acceptedPackets.incrementAndGet();
        maybeLogSummary();

        // 1. Claim next slot in the ring buffer
        OrderBookTick tick = ringBuffer.claim();
        
        // 2. Decode bytes directly into the off-heap struct
        tick.setTimestamp(sequence);
        tick.setInstrumentId(instrumentId);
        tick.setBidSize(bidSize);
        tick.setBidPrice(bidPrice);
        tick.setAskSize(askSize);
        tick.setAskPrice(askPrice);
        
        // 3. Publish to consumer threads
        ringBuffer.commit();
    }
}
