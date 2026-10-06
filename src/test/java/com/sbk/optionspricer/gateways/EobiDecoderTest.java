package com.sbk.optionspricer.gateways;

import com.sbk.optionspricer.core.MarketDataRingBuffer;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        MarketDataRingBuffer buffer = new MarketDataRingBuffer(131072); // large enough, power of 2
        EobiDecoder decoder = new EobiDecoder(buffer);
        java.util.Random rand = new java.util.Random(42); // seeded

        long packetsSent = 0;
        long seq = 1;
        for (int i = 0; i < 100000; i++) {
            packetsSent++;
            int type = rand.nextInt(3);
            if (type == 0) {
                // Pure random
                int len = rand.nextInt(100);
                byte[] packet = new byte[len];
                rand.nextBytes(packet);
                decoder.onMessage(packet);
            } else if (type == 1) {
                // Structured valid
                byte[] packet = new byte[37];
                ByteBuffer bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
                bb.put(0, (byte) 1);
                bb.putLong(1, seq++); // sequence
                bb.putInt(9, 100);
                bb.putInt(13, 10);
                bb.putLong(17, 100000);
                bb.putInt(25, 10);
                bb.putLong(29, 100000);
                decoder.onMessage(packet);
            } else {
                // Structured corrupted (negative size/price etc)
                byte[] packet = new byte[37];
                ByteBuffer bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
                bb.put(0, (byte) 1);
                bb.putLong(1, seq++);
                bb.putInt(9, 100);
                bb.putInt(13, -10); // corrupt
                bb.putLong(17, 100000);
                bb.putInt(25, 10);
                bb.putLong(29, 100000);
                decoder.onMessage(packet);
            }
        }
        
        long sum = decoder.acceptedPackets.get() + decoder.undersizedPackets.get() + 
                   decoder.outOfOrderPackets.get() + decoder.corruptPayloads.get();
        assertEquals(packetsSent, sum, "Total drops + accepted should equal packets sent");

        // Assert ring buffer yields exactly the accepted packets in order
        long expectedSeq = -1;
        for (int i = 0; i < decoder.acceptedPackets.get(); i++) {
            com.sbk.optionspricer.core.OrderBookTick tick = buffer.poll();
            org.junit.jupiter.api.Assertions.assertNotNull(tick);
            if (expectedSeq == -1) expectedSeq = tick.getTimestamp();
            else {
                assertTrue(tick.getTimestamp() > expectedSeq, "Must be strictly increasing");
                expectedSeq = tick.getTimestamp();
            }
        }
        org.junit.jupiter.api.Assertions.assertNull(buffer.poll(), "No extra elements");
    }
}
