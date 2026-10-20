package com.sbk.optionspricer.execution;

/**
 * @param legPrices for a multi-leg fill, the price each leg filled at, in the order the legs were sent; empty when the venue gave
 *                  none, or not for every leg (never half-known)
 */
public record ExecutionResult(String symbol, int filledQuantity, double executionPrice, boolean executed, String message,
                              java.util.List<Double> legPrices) {
    public ExecutionResult {
        legPrices = legPrices == null ? java.util.List.of() : java.util.List.copyOf(legPrices);
    }

    public ExecutionResult(String symbol, int filledQuantity, double executionPrice, boolean executed, String message) {
        this(symbol, filledQuantity, executionPrice, executed, message, java.util.List.of());
    }
}
