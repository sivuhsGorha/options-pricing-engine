package com.sbk.optionspricer.risk;

/**
 * Destination for executed fills. The live tracker records to a {@link FillLedger}; backtests, simulations and
 * tests use {@link #NONE} so they can never write to it.
 */
@FunctionalInterface
public interface FillRecorder {
    /** Called before the fill is booked in memory, so the record never lags what the book shows. */
    void record(String symbol, int executedQty, int multiplier, double price);

    /** Discards fills; for backtests, simulations and tests. */
    FillRecorder NONE = (symbol, executedQty, multiplier, price) -> { };
}
