package com.sbk.optionspricer.data.opra;

import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.instruments.OccSymbol;
import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OpraFeedAdapterTest {

    private static final LocalDate EXPIRY = LocalDate.of(2026, 11, 20);

    @Test
    @DisplayName("OpraBinaryDecoder encodes and decodes 40-byte wire datagrams with zero fidelity loss")
    void binaryEncodingDecodingRoundTrip() {
        OpraMessage original = new OpraMessage(
                "SPY",
                EXPIRY,
                585.50,
                OptionType.CALL,
                4.25,
                150L,
                4.35,
                200L,
                1_700_000_000_123_456_789L
        );

        ByteBuffer buffer = ByteBuffer.allocate(OpraBinaryDecoder.FRAME_SIZE);
        OpraBinaryDecoder.encode(original, buffer);
        assertEquals(OpraBinaryDecoder.FRAME_SIZE, buffer.position());

        buffer.flip();
        OpraMessage decoded = OpraBinaryDecoder.decode(buffer);

        assertEquals("SPY", decoded.symbol());
        assertEquals(EXPIRY, decoded.expiry());
        assertEquals(585.50, decoded.strike(), 1e-9);
        assertEquals(OptionType.CALL, decoded.type());
        assertEquals(4.25, decoded.bid(), 1e-9);
        assertEquals(150L, decoded.bidSize());
        assertEquals(4.35, decoded.ask(), 1e-9);
        assertEquals(200L, decoded.askSize());
        assertEquals(1_700_000_000_123_456_789L, decoded.feedTimestampNanos());
        assertEquals(OccSymbol.format("SPY", EXPIRY, OptionType.CALL, 585.50), decoded.occSymbol());
        assertEquals(4.30, decoded.midPrice(), 1e-9);
        assertTrue(decoded.hasBook());
    }

    @Test
    @DisplayName("OpraRingBuffer enforces power of two and backpressure when full")
    void ringBufferBehavior() {
        assertThrows(IllegalArgumentException.class, () -> new OpraRingBuffer(3));

        OpraRingBuffer ring = new OpraRingBuffer(4);
        assertEquals(4, ring.capacity());
        assertEquals(0, ring.size());

        OpraMessage m1 = new OpraMessage("SPY", EXPIRY, 500.0, OptionType.CALL, 1.0, 10, 1.2, 10, 1L);
        OpraMessage m2 = new OpraMessage("SPY", EXPIRY, 505.0, OptionType.CALL, 1.0, 10, 1.2, 10, 2L);
        OpraMessage m3 = new OpraMessage("SPY", EXPIRY, 510.0, OptionType.CALL, 1.0, 10, 1.2, 10, 3L);
        OpraMessage m4 = new OpraMessage("SPY", EXPIRY, 515.0, OptionType.CALL, 1.0, 10, 1.2, 10, 4L);
        OpraMessage m5 = new OpraMessage("SPY", EXPIRY, 520.0, OptionType.CALL, 1.0, 10, 1.2, 10, 5L);

        assertTrue(ring.offer(m1));
        assertTrue(ring.offer(m2));
        assertTrue(ring.offer(m3));
        assertTrue(ring.offer(m4));
        // Buffer is full (capacity 4)
        assertFalse(ring.offer(m5));
        assertEquals(4, ring.size());

        // Poll one item
        OpraMessage polled = ring.poll();
        assertNotNull(polled);
        assertEquals(500.0, polled.strike(), 1e-9);
        assertEquals(3, ring.size());

        // Drain remaining
        List<OpraMessage> drained = new ArrayList<>();
        int count = ring.drainTo(drained::add);
        assertEquals(3, count);
        assertEquals(0, ring.size());
        assertEquals(List.of(505.0, 510.0, 515.0), drained.stream().map(OpraMessage::strike).toList());
    }

    @Test
    @DisplayName("OpraFeedAdapter streams binary OPRA packets into live quote tables and option chains")
    void streamingIngestionAndChainQuery() throws InterruptedException {
        try (OpraFeedAdapter adapter = new OpraFeedAdapter("SPY", 1024)) {
            CountDownLatch latch = new CountDownLatch(3);
            List<OpraMessage> receivedMessages = new CopyOnWriteArrayList<>();
            adapter.setQuoteListener(msg -> {
                receivedMessages.add(msg);
                latch.countDown();
            });

            long nowNanos = System.currentTimeMillis() * 1_000_000L;

            // Prepare 3 SPY packets and 1 AAPL packet (which should be filtered out)
            OpraMessage call500 = new OpraMessage("SPY", EXPIRY, 500.0, OptionType.CALL, 10.0, 50, 10.2, 60, nowNanos);
            OpraMessage put500 = new OpraMessage("SPY", EXPIRY, 500.0, OptionType.PUT, 2.4, 40, 2.5, 55, nowNanos + 1000);
            OpraMessage call505 = new OpraMessage("SPY", EXPIRY, 505.0, OptionType.CALL, 7.5, 30, 7.7, 45, nowNanos + 2000);
            OpraMessage aaplMsg = new OpraMessage("AAPL", EXPIRY, 220.0, OptionType.CALL, 5.0, 10, 5.2, 10, nowNanos + 3000);

            ByteBuffer buf = ByteBuffer.allocate(OpraBinaryDecoder.FRAME_SIZE);

            // Send call500
            OpraBinaryDecoder.encode(call500, buf);
            buf.flip();
            adapter.onBinaryPacket(buf);

            // Send put500
            buf.clear();
            OpraBinaryDecoder.encode(put500, buf);
            buf.flip();
            adapter.onBinaryPacket(buf);

            // Send call505
            buf.clear();
            OpraBinaryDecoder.encode(call505, buf);
            buf.flip();
            adapter.onBinaryPacket(buf);

            // Send AAPL (should be filtered out)
            buf.clear();
            OpraBinaryDecoder.encode(aaplMsg, buf);
            buf.flip();
            boolean accepted = adapter.onBinaryPacket(buf);
            assertFalse(accepted);

            // Await listener callback processing
            boolean completed = latch.await(2, TimeUnit.SECONDS);
            assertTrue(completed);
            assertEquals(3, receivedMessages.size());

            // Verify MarketSnapshot via optionQuote
            String call500Occ = OccSymbol.format("SPY", EXPIRY, OptionType.CALL, 500.0);
            Optional<MarketSnapshot> snapshotOpt = adapter.optionQuote(call500Occ);
            assertTrue(snapshotOpt.isPresent());
            MarketSnapshot snapshot = snapshotOpt.get();
            assertEquals(10.0, snapshot.bid(), 1e-9);
            assertEquals(10.2, snapshot.ask(), 1e-9);
            assertEquals(10.1, snapshot.last(), 1e-9);
            assertEquals(110L, snapshot.volume()); // 50 + 60
            assertEquals("OPRA", snapshot.source());
            assertEquals(MarketDataStatus.LIVE, snapshot.status());

            // Verify loadedExpiries
            assertEquals(List.of(EXPIRY), adapter.loadedExpiries());

            // Verify OptionChain
            Optional<OptionChain> chainOpt = adapter.chain(EXPIRY);
            assertTrue(chainOpt.isPresent());
            OptionChain chain = chainOpt.get();
            assertEquals("SPY", chain.symbol());
            assertEquals(EXPIRY, chain.expiry());
            assertEquals(3, chain.quotes().size());

            // Verify active quote count
            assertEquals(3, adapter.activeQuoteCount());
        }
    }
}
