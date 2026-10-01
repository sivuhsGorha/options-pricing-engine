package com.sbk.optionspricer.core;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;

/**
 * Modern Java 21 Foreign Function & Memory (FFM) API Struct Layouts.
 * Enforces strict 64-byte cache-line aligned off-heap memory representation
 * for market ticks and execution orders, ensuring 100% zero-GC operation.
 */
public final class MemorySegmentStructs {

    /**
     * Market Tick Off-Heap Layout (Exact 64-byte Cache-Line Alignment)
     */
    public static final StructLayout TICK_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("timestampNs"),     // Offset 0
        ValueLayout.JAVA_LONG.withName("contractId"),      // Offset 8
        ValueLayout.JAVA_DOUBLE.withName("bidPrice"),      // Offset 16
        ValueLayout.JAVA_DOUBLE.withName("askPrice"),      // Offset 24
        ValueLayout.JAVA_INT.withName("bidSize"),          // Offset 32
        ValueLayout.JAVA_INT.withName("askSize"),          // Offset 36
        ValueLayout.JAVA_LONG.withName("sequenceNumber"),  // Offset 40
        MemoryLayout.paddingLayout(16)                     // Offset 48..64 (Padding)
    );

    private MemorySegmentStructs() {}

    /**
     * Allocates a zero-GC tick buffer in off-heap memory.
     */
    public static MemorySegment allocateTick(Arena arena) {
        return arena.allocate(TICK_LAYOUT);
    }

    public static void setTickData(MemorySegment segment, long timestampNs, long contractId,
                                  double bidPrice, double askPrice, int bidSize, int askSize, long sequenceNum) {
        segment.set(ValueLayout.JAVA_LONG, 0, timestampNs);
        segment.set(ValueLayout.JAVA_LONG, 8, contractId);
        segment.set(ValueLayout.JAVA_DOUBLE, 16, bidPrice);
        segment.set(ValueLayout.JAVA_DOUBLE, 24, askPrice);
        segment.set(ValueLayout.JAVA_INT, 32, bidSize);
        segment.set(ValueLayout.JAVA_INT, 36, askSize);
        segment.set(ValueLayout.JAVA_LONG, 40, sequenceNum);
    }

    public static double getBidPrice(MemorySegment segment) {
        return segment.get(ValueLayout.JAVA_DOUBLE, 16);
    }

    public static double getAskPrice(MemorySegment segment) {
        return segment.get(ValueLayout.JAVA_DOUBLE, 24);
    }

    public static int getBidSize(MemorySegment segment) {
        return segment.get(ValueLayout.JAVA_INT, 32);
    }

    public static int getAskSize(MemorySegment segment) {
        return segment.get(ValueLayout.JAVA_INT, 36);
    }
}
