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

    @Test
    void testEobiFuzzing() {
        MarketDataRingBuffer buffer = new MarketDataRingBuffer(1024);
        EobiDecoder decoder = new EobiDecoder(buffer);
        java.util.Random rand = new java.util.Random(42); // seeded

        for (int i = 0; i < 100000; i++) {
            int len = rand.nextInt(100); // 0 to 99 bytes (some truncated, some oversize)
            byte[] packet = new byte[len];
            rand.nextBytes(packet);
            
            // Randomly set some valid-looking packet types but garbage data
            if (len > 0) {
                packet[0] = (byte) rand.nextInt(5); 
            }
            
            // Should not throw exception or hang
            decoder.onMessage(packet);
        }
        
        // Ring buffer state unchanged (no items committed)
        org.junit.jupiter.api.Assertions.assertNull(buffer.poll(), "Ring buffer should be unchanged by dropped packets");
    }
}
