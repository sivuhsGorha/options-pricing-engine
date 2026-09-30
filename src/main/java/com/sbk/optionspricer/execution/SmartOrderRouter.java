package com.sbk.optionspricer.execution;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Smart Order Router (SOR) and Gateway.
 * Takes validated orders and translates them into the raw binary payload 
 * required by the exchange (e.g., Eurex ETI - Enhanced Trading Interface).
 */
public class SmartOrderRouter {

    private final PreTradeRiskFilter riskFilter;

    public SmartOrderRouter(PreTradeRiskFilter riskFilter) {
        this.riskFilter = riskFilter;
    }

    /**
     * Attempts to send an order to the exchange.
     * @return true if sent, false if rejected by risk filter
     */
    public boolean routeOrder(Order order) {
        // 1. Mandatory sub-microsecond risk check
        if (!riskFilter.checkRisk(order)) {
            return false;
        }
        
        // 2. Translate to Exchange Binary Payload (Eurex ETI Mock)
        // Simulated ETI NewOrderSingle payload:
        // [0]   MsgType (1 byte) = 2 (NewOrder)
        // [1-4] InstrumentID (4 bytes)
        // [5]   Side (1 byte) = 1 (Buy), 2 (Sell)
        // [6-9] Quantity (4 bytes)
        // [10-17] Price (8 bytes, scaled by 10,000)
        
        byte[] networkPayload = new byte[18];
        ByteBuffer buffer = ByteBuffer.wrap(networkPayload).order(ByteOrder.LITTLE_ENDIAN);
        
        buffer.put((byte) 2);
        buffer.putInt(order.instrumentId());
        buffer.put((byte) (order.isBuy() ? 1 : 2));
        buffer.putInt(order.quantity());
        buffer.putLong((long)(order.price() * 10000));
        
        // 3. Dispatch to NIC (Network Interface Card) via socket
        // In this simulated environment, we just log it as successful
        transmitToExchange(networkPayload);
        
        return true;
    }
    
    private void transmitToExchange(byte[] payload) {
        // Mock socket write
        // System.out.println("-> [NETWORK] Transmitted " + payload.length + " bytes to Exchange ETI port.");
    }
}
