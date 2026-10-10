package com.sbk.optionspricer.execution;

/**
 * A plain record: nothing on the order path needs it off-heap.
 * Prices are doubles here; PriceScale converts to exact decimal ticks where a wire format needs them.
 */
public record Order(int instrumentId, boolean isBuy, int quantity, double price) {
}
