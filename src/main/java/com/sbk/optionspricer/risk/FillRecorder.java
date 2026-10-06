package com.sbk.optionspricer.risk;

/**
 * Destination for executed fills. Live trading records to the authoritative {@link FillLedger};
 * backtests and simulations use {@link #NONE} so they can never write to it.
 */
@FunctionalInterface
public interface FillRecorder {

    void record(String symbol, int executedQty, int multiplier);

    /** The authoritative append-only ledger. */
    FillRecorder LEDGER = FillLedger::recordFill;

    /** Discards fills; for backtests and simulations. */
    FillRecorder NONE = (symbol, executedQty, multiplier) -> { };
}
