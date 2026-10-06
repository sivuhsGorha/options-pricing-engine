package com.sbk.optionspricer.gateways;

/**
 * Core order model for execution and routing.
 */
public class OrderTicket {

    public enum OrderType {
        MARKET, LIMIT
    }

    public enum Side {
        BUY, SELL
    }

    private final String orderId;
    private final String symbol;
    private final Side side;
    private final int quantity;
    private final double price;
    private final OrderType type;

    private volatile OrderState state;
    private volatile int filledQuantity;

    public OrderTicket(String orderId, String symbol, Side side, int quantity, double price, OrderType type) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        this.orderId = orderId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.type = type;
        this.state = OrderState.NEW;
        this.filledQuantity = 0;
    }

    public String getOrderId() { return orderId; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public int getQuantity() { return quantity; }
    public double getPrice() { return price; }
    public OrderType getType() { return type; }

    public OrderState getState() { return state; }
    public void setState(OrderState state) { this.state = state; }

    public int getFilledQuantity() { return filledQuantity; }
    public void addFill(int fillQty) {
        if (fillQty <= 0) throw new IllegalArgumentException("Fill quantity must be positive");
        this.filledQuantity += fillQty;
        if (this.filledQuantity >= this.quantity) {
            this.filledQuantity = this.quantity;
            this.state = OrderState.FILLED;
        } else {
            this.state = OrderState.PARTIALLY_FILLED;
        }
    }
}
