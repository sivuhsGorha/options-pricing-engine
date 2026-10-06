package com.sbk.optionspricer.gateways;

/**
 * Encodes OrderTickets into standard FIX 4.4 (Financial Information eXchange) messages.
 */
public class FixMessageEncoder {

    private static final char SOH = '\u0001';

    /**
     * Encodes an OrderTicket into a FIX 4.4 New Order Single (35=D) message.
     */
    public static String encodeNewOrderSingle(OrderTicket order) {
        if (order == null) {
            throw new IllegalArgumentException("order must not be null");
        }
        validateFixField("orderId", order.getOrderId());
        validateFixField("symbol", order.getSymbol());

        StringBuilder body = new StringBuilder();
        
        // 35=MsgType (D = New Order Single)
        body.append("35=D").append(SOH);
        
        // 11=ClOrdID
        body.append("11=").append(order.getOrderId()).append(SOH);
        
        // 55=Symbol
        body.append("55=").append(order.getSymbol()).append(SOH);
        
        // 54=Side (1 = Buy, 2 = Sell)
        body.append("54=").append(order.getSide() == OrderTicket.Side.BUY ? "1" : "2").append(SOH);
        
        // 38=OrderQty
        body.append("38=").append(order.getQuantity()).append(SOH);
        
        // 40=OrdType (1 = Market, 2 = Limit)
        if (order.getType() == OrderTicket.OrderType.MARKET) {
            body.append("40=1").append(SOH);
        } else {
            body.append("40=2").append(SOH);
            // 44=Price
            body.append("44=").append(order.getPrice()).append(SOH);
        }

        String bodyString = body.toString();
        
        // 8=BeginString
        String headerPrefix = "8=FIX.4.4" + SOH + "9=" + bodyString.length() + SOH;
        
        String messageWithoutChecksum = headerPrefix + bodyString;
        
        // 10=CheckSum (Modulo 256 sum of all characters)
        int checksum = 0;
        for (int i = 0; i < messageWithoutChecksum.length(); i++) {
            checksum += messageWithoutChecksum.charAt(i);
        }
        checksum %= 256;
        
        return messageWithoutChecksum + "10=" + String.format("%03d", checksum) + SOH;
    }

    private static void validateFixField(String fieldName, String value) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        if (value.indexOf(SOH) >= 0 || value.indexOf('=') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("FIX Protocol Injection blocked: " + fieldName + " contains prohibited delimiters (SOH, '=', or newline)");
        }
    }
}
