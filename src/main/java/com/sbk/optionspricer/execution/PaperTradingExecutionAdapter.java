package com.sbk.optionspricer.execution;

/**
 * Simulated broker adapter used for paper-trading and end-to-end validation.
 * It accepts orders, simulates a fill at a near-mid execution price, and updates
 * the shared PositionTracker so risk and dashboard state reflect the trade.
 */
public class PaperTradingExecutionAdapter implements ExchangeTransport {
    private final PositionTracker positionTracker;
    private final double slippageBps;
    private volatile ExecutionResult lastResult;

    public PaperTradingExecutionAdapter(PositionTracker positionTracker) {
        this(positionTracker, 25.0d);
    }

    public PaperTradingExecutionAdapter(PositionTracker positionTracker, double slippageBps) {
        this.positionTracker = positionTracker;
        this.slippageBps = Double.isFinite(slippageBps) && slippageBps >= 0.0 ? slippageBps : 25.0d;
    }

    @Override
    public ExecutionResult transmit(Order order, String symbol, double bid, double ask) {
        return executeOrder(order, symbol, bid, ask);
    }

    public ExecutionResult executeOrder(Order order, String symbol, double bid, double ask) {
        if (order == null) {
            throw new IllegalArgumentException("order must not be null");
        }
        String normalizedSymbol = (symbol == null || symbol.isBlank()) ? "UNKNOWN" : symbol.trim().toUpperCase();

        // Fill against the book's far side when there is a book; otherwise against the order's own price.
        // (An earlier version fell back to a hard-coded 100.0, which booked fictitious fills for any symbol
        // whose provider publishes no bid/ask.)
        double executionPrice;
        if (order.isBuy()) {
            double reference = Double.isFinite(ask) && ask > 0.0 ? ask : (Double.isFinite(bid) && bid > 0.0 ? bid : order.price());
            executionPrice = reference * (1.0 + slippageBps / 10_000.0);
        } else {
            double reference = Double.isFinite(bid) && bid > 0.0 ? bid : (Double.isFinite(ask) && ask > 0.0 ? ask : order.price());
            executionPrice = reference * (1.0 - slippageBps / 10_000.0);
        }

        int filledQuantity = order.quantity();

        ExecutionResult result = new ExecutionResult(normalizedSymbol, filledQuantity, executionPrice, true, "Executed");
        this.lastResult = result;
        return result;
    }

    public ExecutionResult getLastResult() {
        return lastResult;
    }
}
