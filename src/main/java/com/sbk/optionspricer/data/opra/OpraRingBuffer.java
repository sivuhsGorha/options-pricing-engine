package com.sbk.optionspricer.data.opra;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Ultra-low-latency, zero-allocation circular ring buffer for OPRA feed ingestion (LMAX Disruptor pattern).
 *
 * <p>Uses power-of-two capacity with bitwise masking for fast modulo indexing without division instructions.
 */
public final class OpraRingBuffer {

    private final OpraMessage[] ring;
    private final int mask;
    private final int capacity;

    private final AtomicLong writeSequence = new AtomicLong(0L);
    private final AtomicLong readSequence = new AtomicLong(0L);

    public OpraRingBuffer(int capacityPowerOfTwo) {
        if (Integer.bitCount(capacityPowerOfTwo) != 1) {
            throw new IllegalArgumentException("Capacity must be a power of two, got: " + capacityPowerOfTwo);
        }
        this.capacity = capacityPowerOfTwo;
        this.mask = capacityPowerOfTwo - 1;
        this.ring = new OpraMessage[capacityPowerOfTwo];
    }

    public synchronized boolean offer(OpraMessage message) {
        long currentRead = readSequence.get();
        long currentWrite = writeSequence.get();

        if (currentWrite - currentRead >= capacity) {
            return false; // Ring buffer full (backpressure)
        }

        int index = (int) (currentWrite & mask);
        ring[index] = message;
        writeSequence.set(currentWrite + 1L);
        return true;
    }

    public synchronized OpraMessage poll() {
        long currentRead = readSequence.get();
        long currentWrite = writeSequence.get();

        if (currentRead >= currentWrite) {
            return null; // Buffer empty
        }

        int index = (int) (currentRead & mask);
        OpraMessage message = ring[index];
        ring[index] = null; // Help GC / reuse
        readSequence.set(currentRead + 1L);
        return message;
    }

    public int drainTo(Consumer<OpraMessage> consumer) {
        long currentRead = readSequence.get();
        long currentWrite = writeSequence.get();
        int drained = 0;

        while (currentRead < currentWrite) {
            int index = (int) (currentRead & mask);
            OpraMessage message = ring[index];
            ring[index] = null;
            if (message != null) {
                consumer.accept(message);
                drained++;
            }
            currentRead++;
        }

        readSequence.set(currentRead);
        return drained;
    }

    public int size() {
        return (int) Math.max(0L, writeSequence.get() - readSequence.get());
    }

    public int capacity() {
        return capacity;
    }
}
