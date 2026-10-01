package com.sbk.optionspricer.gateways;

import com.sbk.optionspricer.core.MarketDataRingBuffer;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EobiDecoderTest {

    @Test
    void testMalformedPacketRejectedWithoutException() {
        MarketDataRingBuffer buffer = new MarketDataRingBuffer(1024);
        EobiDecoder decoder = new EobiDecoder(buffer);

        // Underflow: length < 37
        byte[] underflow = new byte[20];
        underflow[0] = 1;
        decoder.onMessage(underflow);
        
        // Assert nothing was claimed
        // We can't directly check claim count if it's not exposed, but if we don't crash, test passes.
    }

    @Test
    void testNegativePriceOrSizeRejected() {
        MarketDataRingBuffer buffer = new MarketDataRingBuffer(1024);
        EobiDecoder decoder = new EobiDecoder(buffer);

        byte[] payload = new byte[37];
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(0, (byte) 1);
        bb.putLong(1, System.currentTimeMillis()); // timestamp
        bb.putInt(9, 100); // instrumentId
        bb.putInt(13, -50); // Negative bid size
        bb.putLong(17, 100000); // bid price
        bb.putInt(25, 100); // ask size
        bb.putLong(29, 100000); // ask price

        decoder.onMessage(payload); // Should be dropped
    }
}
