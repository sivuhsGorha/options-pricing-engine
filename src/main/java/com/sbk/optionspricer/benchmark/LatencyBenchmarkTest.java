package com.sbk.optionspricer.benchmark;

import com.sbk.optionspricer.tuning.JitCompilerWarmer;
import com.sbk.optionspricer.volatility.SabrModel;

public class LatencyBenchmarkTest {

    public static void main(String[] args) {
        System.out.println("=== Phase 5.4: End-to-End Latency Verification ===");
        
        // 1. Force the JVM C2 Compiler to optimize the code paths
        JitCompilerWarmer.warmUp();
        
        int iterations = 1_000_000;
        System.out.println("\n[BENCHMARK] Executing 1,000,000 SABR Hagan implied volatility calculations...");
        
        // Pre-allocate array to avoid GC during benchmark
        double[] results = new double[iterations];
        
        long startNano = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            // Slight variations in strike to prevent JVM loop unrolling / constant folding
            double strike = 100.0 + (i % 10);
            results[i] = SabrModel.impliedVolatility(100.0, strike, 1.0, 0.25, 1.0, -0.6, 0.4);
        }
        
        long endNano = System.nanoTime();
        long totalDurationNano = endNano - startNano;
        
        double averageLatencyNano = (double) totalDurationNano / iterations;
        double averageLatencyMicro = averageLatencyNano / 1_000.0;
        
        System.out.printf("Total Time: %,d ms%n", totalDurationNano / 1_000_000);
        System.out.printf("Average Latency per Option: %.2f nanoseconds (%.4f microseconds)%n", averageLatencyNano, averageLatencyMicro);
        
        // Prevent dead code elimination
        if (results[0] == 0) {
            System.out.println("Error");
        }
        
        if (averageLatencyMicro < 10.0) {
            System.out.println("STATUS: PASSED. Sub-10 microsecond latency confirmed.");
        } else {
            System.out.println("STATUS: FAILED. Latency too high.");
        }
    }
}
