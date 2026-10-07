package com.sbk.optionspricer.core;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Single-Producer Single-Consumer (SPSC) Lock-Free Ring Buffer.
 * Off-heap SPSC ring in the spirit of the LMAX Disruptor (it is not the Disruptor, and its latency is unmeasured).
 * 
 * Pre-allocates a massive contiguous block of off-heap memory and slices it 
 * into flyweight OrderBookTicks. Sequences are padded to prevent false sharing.
 */
public class MarketDataRingBuffer {

    private final int capacity;
    private final int mask;
    private final OrderBookTick[] ticks;
    private final Arena arena;

    // Cache line padding (typically 64-128 bytes) to prevent false sharing
    @SuppressWarnings("unused")
    private long p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15;
    
    private volatile long consumerSequence = 0;
    /** Consumer-thread only: true while the tick returned by the last poll() is still being read. */
    private boolean slotHeld;
    
    @SuppressWarnings("unused")
    private long c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15;
    
    private volatile long producerSequence = 0;
    
    @SuppressWarnings("unused")
    private long q1, q2, q3, q4, q5, q6, q7, q8, q9, q10, q11, q12, q13, q14, q15;

    // Fast memory barrier intrinsics
    private static final VarHandle PRODUCER_SEQ;
    private static final VarHandle CONSUMER_SEQ;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            PRODUCER_SEQ = l.findVarHandle(MarketDataRingBuffer.class, "producerSequence", long.class);
            CONSUMER_SEQ = l.findVarHandle(MarketDataRingBuffer.class, "consumerSequence", long.class);
        } catch (ReflectiveOperationException e) {
            throw new Error(e);
        }
    }

    public MarketDataRingBuffer(int capacity) {
        if (Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("Capacity must be a power of 2");
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.ticks = new OrderBookTick[capacity];
        this.arena = Arena.ofShared();
        
        // Allocate ONE massive contiguous chunk of off-heap memory
        MemorySegment slab = arena.allocate(OrderBookTick.SIZE_BYTES * capacity);
        
        for (int i = 0; i < capacity; i++) {
            ticks[i] = new OrderBookTick();
            MemorySegment slice = slab.asSlice(i * OrderBookTick.SIZE_BYTES, OrderBookTick.SIZE_BYTES);
            ticks[i].wrap(slice);
        }
    }

    /**
     * Producer: Claims the next available slot.
     * Blocks (spin-waits) if the buffer is full.
     */
    public OrderBookTick claim() {
        long nextSequence = (long) PRODUCER_SEQ.getOpaque(this);
        long cachedConsumerSeq = (long) CONSUMER_SEQ.getAcquire(this);
        
        // Spin-wait until there is room
        while (nextSequence - cachedConsumerSeq >= capacity) {
            Thread.onSpinWait();
            cachedConsumerSeq = (long) CONSUMER_SEQ.getAcquire(this);
        }
        
        return ticks[(int)(nextSequence & mask)];
    }

    /**
     * Producer: Publishes the claimed slot to the consumer.
     */
    public void commit() {
        long currentSeq = (long) PRODUCER_SEQ.getOpaque(this);
        PRODUCER_SEQ.setRelease(this, currentSeq + 1);
    }

    /**
     * Consumer: Polls for the next available tick.
     *
     * <p>The returned tick is a view of a ring slot and stays valid only until the next call to
     * {@code poll()}: that call is what releases the slot back to the producer. (Releasing it before
     * returning let the producer overwrite the tick while the caller was still reading it.)
     * Copy anything needed beyond that point.
     *
     * @return the tick, or null if empty
     */
    public OrderBookTick poll() {
        long currentConsumerSeq = (long) CONSUMER_SEQ.getOpaque(this);
        if (slotHeld) {
            currentConsumerSeq++;
            CONSUMER_SEQ.setRelease(this, currentConsumerSeq);
            slotHeld = false;
        }
        long currentProducerSeq = (long) PRODUCER_SEQ.getAcquire(this);

        if (currentProducerSeq > currentConsumerSeq) {
            slotHeld = true;
            return ticks[(int)(currentConsumerSeq & mask)];
        }
        return null;
    }

    /**
     * Closes the off-heap memory arena. Safe to call more than once. Stop the producer and consumer first:
     * touching a tick after this throws.
     */
    public void shutdown() {
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
