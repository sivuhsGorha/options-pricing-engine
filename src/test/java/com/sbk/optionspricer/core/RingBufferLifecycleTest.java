package com.sbk.optionspricer.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** A slot handed to the consumer must stay untouched until the consumer asks for the next one. */
class RingBufferLifecycleTest {

    @Test
    void producerCannotOverwriteATickTheConsumerIsStillReading() throws Exception {
        MarketDataRingBuffer buffer = new MarketDataRingBuffer(1);
        try {
            buffer.claim();
            buffer.commit();
            assertNotNull(buffer.poll(), "the committed tick is delivered");

            CompletableFuture<OrderBookTick> producer = CompletableFuture.supplyAsync(buffer::claim);
            assertThrows(TimeoutException.class, () -> producer.get(300, TimeUnit.MILLISECONDS),
                    "the only slot is still being read, so the producer must wait");

            assertNull(buffer.poll(), "nothing further to read; this call releases the slot just read");
            assertNotNull(producer.get(5, TimeUnit.SECONDS), "now the producer may reuse the slot");
        } finally {
            buffer.shutdown();
        }
    }

    @Test
    void shutdownIsIdempotent() {
        MarketDataRingBuffer buffer = new MarketDataRingBuffer(4);
        buffer.shutdown();
        assertDoesNotThrow(buffer::shutdown);
    }
}
