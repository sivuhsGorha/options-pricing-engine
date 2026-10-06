package com.sbk.optionspricer.core;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Flyweight wrapper over an off-heap MemorySegment representing an L1 Order Book Tick.
 * 
 * Allows zero-allocation reads and writes directly to native OS memory.
 * Designed explicitly for Java 21's Foreign Function & Memory (FFM) API.
 * 
 * Memory Layout (40 bytes total, strictly 8-byte aligned):
 * [0-7]   long   timestamp (nanoseconds)
 * [8-11]  int    instrumentId
 * [12-15] int    bidSize
 * [16-23] double bidPrice
 * [24-27] int    askSize
 * [28-31] int    (padding for strict 8-byte alignment)
 * [32-39] double askPrice
 */
public class OrderBookTick {

    public static final long SIZE_BYTES = 40;

    private static final long TIMESTAMP_OFFSET = 0;
    private static final long INSTRUMENT_ID_OFFSET = 8;
    private static final long BID_SIZE_OFFSET = 12;
    private static final long BID_PRICE_OFFSET = 16;
    private static final long ASK_SIZE_OFFSET = 24;
    private static final long ASK_PRICE_OFFSET = 32;

    // The underlying off-heap memory segment this flyweight currently points to
    private MemorySegment segment;

    /**
     * Binds this flyweight instance to a specific memory segment.
     * In a SPSC ring buffer, this is called once during startup to pre-link objects.
     */
    public void wrap(MemorySegment segment) {
        if (segment.byteSize() < SIZE_BYTES) {
            throw new IllegalArgumentException("Segment too small for OrderBookTick");
        }
        this.segment = segment;
    }

    // --- Fast Zero-Allocation Getters ---
    
    public long getTimestamp() {
        return segment.get(ValueLayout.JAVA_LONG, TIMESTAMP_OFFSET);
    }

    public int getInstrumentId() {
        return segment.get(ValueLayout.JAVA_INT, INSTRUMENT_ID_OFFSET);
    }

    public int getBidSize() {
        return segment.get(ValueLayout.JAVA_INT, BID_SIZE_OFFSET);
    }

    public double getBidPrice() {
        return segment.get(ValueLayout.JAVA_DOUBLE, BID_PRICE_OFFSET);
    }

    public int getAskSize() {
        return segment.get(ValueLayout.JAVA_INT, ASK_SIZE_OFFSET);
    }

    public double getAskPrice() {
        return segment.get(ValueLayout.JAVA_DOUBLE, ASK_PRICE_OFFSET);
    }

    // --- Fast Zero-Allocation Setters ---

    public void setTimestamp(long timestamp) {
        segment.set(ValueLayout.JAVA_LONG, TIMESTAMP_OFFSET, timestamp);
    }

    public void setInstrumentId(int instrumentId) {
        segment.set(ValueLayout.JAVA_INT, INSTRUMENT_ID_OFFSET, instrumentId);
    }

    public void setBidSize(int bidSize) {
        segment.set(ValueLayout.JAVA_INT, BID_SIZE_OFFSET, bidSize);
    }

    public void setBidPrice(double bidPrice) {
        segment.set(ValueLayout.JAVA_DOUBLE, BID_PRICE_OFFSET, bidPrice);
    }

    public void setAskSize(int askSize) {
        segment.set(ValueLayout.JAVA_INT, ASK_SIZE_OFFSET, askSize);
    }

    public void setAskPrice(double askPrice) {
        segment.set(ValueLayout.JAVA_DOUBLE, ASK_PRICE_OFFSET, askPrice);
    }
    
    /** Calculate exact mid-price on the fly without storing it. */
    public double getMidPrice() {
        return (getBidPrice() + getAskPrice()) * 0.5;
    }
}
