package com.sbk.optionspricer.core;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

public class MemorySegmentTest {
    public static void main(String[] args) {
        System.out.println("Allocating off-heap memory...");
        
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(OrderBookTick.SIZE_BYTES);
            
            OrderBookTick tick = new OrderBookTick();
            tick.wrap(segment);
            
            tick.setTimestamp(System.nanoTime());
            tick.setInstrumentId(101);
            tick.setBidSize(50);
            tick.setBidPrice(104.5);
            tick.setAskSize(100);
            tick.setAskPrice(105.0);
            
            System.out.println("Tick written to native memory successfully.");
            System.out.println("Mid Price calculated off-heap: " + tick.getMidPrice());
            System.out.println("Timestamp: " + tick.getTimestamp());
        }
        
        System.out.println("Off-heap memory freed cleanly.");
    }
}
