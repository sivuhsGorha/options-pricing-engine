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

    /**
     * Transmits a multi-leg complex order atomically.
     */
    default ExecutionResult transmitMultiLeg(java.util.List<Order> legs, java.util.List<String> symbols,
                                             java.util.List<Double> bids, java.util.List<Double> asks, double netLimitPrice) {
        if (legs == null || symbols == null || legs.isEmpty() || legs.size() != symbols.size()) {
            return new ExecutionResult("UNKNOWN", 0, 0.0, false, "Invalid multi-leg parameters");
        }
        for (int i = 0; i < legs.size(); i++) {
            double b = (bids != null && i < bids.size()) ? bids.get(i) : Double.NaN;
            double a = (asks != null && i < asks.size()) ? asks.get(i) : Double.NaN;
            ExecutionResult res = transmit(legs.get(i), symbols.get(i), b, a);
            if (!res.executed()) {
                return res;
            }
        }
        return new ExecutionResult(symbols.get(0), legs.get(0).quantity(), netLimitPrice, true, "Multi-leg executed");
    }

    /**
     * The same, told which legs close a position already held ({@code closing.get(i)}): a venue that names an order's intent
     * needs it, since a sell that closes a long and a sell that writes an option are different orders. The net price is an
     * amount: a debit when every leg buys, the proceeds when every leg sells. A transport that does not care ignores it.
     */
    default ExecutionResult transmitMultiLeg(java.util.List<Order> legs, java.util.List<String> symbols,
                                             java.util.List<Double> bids, java.util.List<Double> asks, double netLimitPrice,
                                             java.util.List<Boolean> closing) {
        return transmitMultiLeg(legs, symbols, bids, asks, netLimitPrice);
    }
}
