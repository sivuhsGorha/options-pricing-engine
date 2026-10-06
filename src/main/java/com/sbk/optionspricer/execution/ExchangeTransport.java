package com.sbk.optionspricer.execution;

public interface ExchangeTransport {
    /**
     * Transmits an order for execution.
     * @param order the order to transmit
     * @param symbol the asset symbol
     * @param bid the current bid
     * @param ask the current ask
     * @return ExecutionResult outcome
     */
    ExecutionResult transmit(Order order, String symbol, double bid, double ask);
}
