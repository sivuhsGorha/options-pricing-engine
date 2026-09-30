package com.sbk.optionspricer.execution;

/**
 * Basic order representation. 
 * In a true HFT system, this would be an off-heap MemorySegment just like OrderBookTick.
 * For this exercise, a simple record suffices to demonstrate the routing logic.
 */
public record Order(int instrumentId, boolean isBuy, int quantity, double price) {
}
