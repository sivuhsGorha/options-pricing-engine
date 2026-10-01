package com.sbk.optionspricer.execution;

public interface ExchangeTransport {
    /**
     * Transmits a binary payload to the exchange.
     * @param payload the byte array
     * @return true if acknowledged by the exchange transport layer
     */
    boolean transmit(byte[] payload);
}
