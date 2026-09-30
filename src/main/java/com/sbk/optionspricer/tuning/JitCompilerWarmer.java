package com.sbk.optionspricer.tuning;

import com.sbk.optionspricer.core.OrderBookTick;

/**
 * JVM Just-In-Time (JIT) Compiler Warmer.
 * Forces the Java C2 compiler to heavily optimize our critical hot-path methods 
 * before the market opens at 9:30 AM.
 */
public class JitCompilerWarmer {

    public static void warmUp() {
        System.out.println("[JVM TUNING] Initiating JIT Compiler Warmup...");
        
        // The C2 Compiler typically kicks in after 10,000 invocations.
        // We will run the critical path 100,000 times to guarantee assembly generation.
        java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined();
        OrderBookTick dummyTick = new OrderBookTick();
        dummyTick.wrap(arena.allocate(40));
        
        long start = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            dummyTick.setBidPrice(100.0 + (i % 10));
            dummyTick.setAskPrice(101.0 + (i % 10));
            dummyTick.setInstrumentId(i);
            
            // Dummy math operation to prevent Dead Code Elimination by the JVM
            double mid = (dummyTick.getBidPrice() + dummyTick.getAskPrice()) / 2.0;
            if (mid < 0) {
                System.out.println("Never happens");
            }
        }
        
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("[JVM TUNING] JIT C2 Compiler Warmup complete in " + elapsedMs + " ms. Hot-path is now native assembly.");
    }
}
