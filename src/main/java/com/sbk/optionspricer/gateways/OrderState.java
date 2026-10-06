package com.sbk.optionspricer.gateways;

/**
 * Lifecycle states of an execution order matching standard FIX specifications.
 */
public enum OrderState {
    NEW,
    PENDING_NEW,
    PARTIALLY_FILLED,
    FILLED,
    PENDING_CANCEL,
    CANCELED,
    REJECTED
}
