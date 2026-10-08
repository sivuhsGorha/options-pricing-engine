package com.sbk.optionspricer.execution;

import java.time.Instant;

/**
 * What an operator may change at runtime: trip or clear the trading halt, and switch the strategy on or off.
 * Every call returns the resulting state so the caller can show it without a second request.
 */
public interface OperatorControls {

    /**
     * @param mode {@code momentum} (shares on a price move) or {@code vol_spread} (front-month straddle against the surface)
     * @param note the strategy's latest decision in its own words, or null before it has run
     */
    record ControlState(boolean halted, String haltReason, Instant haltedAt, boolean strategyEnabled,
                        String symbol, double triggerPct, int baseQuantity, String mode, String note) {}

    ControlState state();

    /** Trips the trading halt; every order is rejected until {@link #resume()}. A blank reason is recorded as operator-initiated. */
    ControlState halt(String reason);

    ControlState resume();

    ControlState setStrategyEnabled(boolean enabled);
}
