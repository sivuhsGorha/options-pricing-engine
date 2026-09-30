package com.sbk.optionspricer.core;

public class RingBufferTest {

    public static void main(String[] args) throws InterruptedException {
        int capacity = 1024 * 64; // 65,536 power of 2
        MarketDataRingBuffer ringBuffer = new MarketDataRingBuffer(capacity);
        int totalMessages = 10_000_000;

        System.out.println("Starting LMAX-style SPSC Ring Buffer IPC test...");
        
        Thread consumer = new Thread(() -> {
            long received = 0;
            long start = System.nanoTime();
            
            while (received < totalMessages) {
                OrderBookTick tick = ringBuffer.poll();
                if (tick != null) {
                    received++;
                } else {
                    Thread.onSpinWait();
                }
            }
            
            long elapsed = System.nanoTime() - start;
            double ms = elapsed / 1_000_000.0;
            System.out.printf("Consumer received %,d messages in %.2f ms%n", received, ms);
            System.out.printf("Throughput: %,.0f messages / second%n", (received / (ms / 1000.0)));
        });

        Thread producer = new Thread(() -> {
            for (int i = 0; i < totalMessages; i++) {
                OrderBookTick tick = ringBuffer.claim();
                tick.setInstrumentId(i); // write payload
                tick.setBidPrice(100.0);
                ringBuffer.commit();
            }
        });

        // Warm up / Start
        consumer.start();
        producer.start();

        consumer.join();
        producer.join();

        ringBuffer.shutdown();
        System.out.println("Ring Buffer off-heap memory closed.");
    }
}
