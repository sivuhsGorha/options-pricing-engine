package com.sbk.optionspricer;

import com.sbk.optionspricer.benchmark.InstitutionalSuiteTest;
import com.sbk.optionspricer.benchmark.LatencyBenchmarkTest;
import com.sbk.optionspricer.benchmark.Level3UpgradesTest;
import com.sbk.optionspricer.core.RingBufferTest;
import com.sbk.optionspricer.core.UnifiedQuantEngine;
import com.sbk.optionspricer.gateways.GatewayTest;
import com.sbk.optionspricer.risk.RiskEngineTest;
import com.sbk.optionspricer.volatility.VolatilitySurfaceTest;

/**
 * Unified Main Entry Point for the AURA-OPT Options Pricing Engine.
 * Executes core pricing models, benchmark suites, and launches the unified
 * real-time cross-functional quantitative engine.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println("=========================================================================");
        System.out.println("      AURA-OPT INSTITUTIONAL OPTIONS PRICING & EXECUTION PLATFORM        ");
        System.out.println("=========================================================================");

        // 1. Core Mathematical Models
        System.out.println("\n[MODULE 1] Core Mathematical Pricing Models");
        OptionParameters params = OptionParameters.noDividend(100.0, 105.0, 0.5, 0.05, 0.25);
        double callPrice = BlackScholesPricer.price(OptionType.CALL, params);
        double putPrice = BlackScholesPricer.price(OptionType.PUT, params);
        System.out.printf(java.util.Locale.ROOT, "Black-Scholes Call: %.4f | Put: %.4f | Parity: %.6f%n",
                callPrice, putPrice, callPrice - putPrice);

        // 2. Lock-Free Ring Buffer IPC
        System.out.println("\n[MODULE 2] LMAX Ring Buffer IPC Benchmark");
        RingBufferTest.main(new String[0]);

        // 3. Volatility Calibration
        System.out.println("\n[MODULE 3] SABR & SVI Volatility Calibration");
        VolatilitySurfaceTest.main(new String[0]);

        // 4. Exchange Gateway Replay
        System.out.println("\n[MODULE 4] Simulated Binary Exchange Gateway Replay");
        GatewayTest.main(new String[0]);

        // 5. Enterprise Risk Engine
        System.out.println("\n[MODULE 5] Portfolio Risk Engine & SPAN Margin Simulator");
        RiskEngineTest.main(new String[0]);

        // 6. Latency Benchmark
        System.out.println("\n[MODULE 6] High-Frequency Latency Verification");
        LatencyBenchmarkTest.main(new String[0]);

        // 7. Level 3 Proprietary Upgrades
        System.out.println("\n[MODULE 7] Level 3 Proprietary Upgrades Verification");
        Level3UpgradesTest.main(new String[0]);

        // 8. Institutional Quantitative Upgrades
        System.out.println("\n[MODULE 8] Institutional Quantitative & Architecture Upgrades");
        InstitutionalSuiteTest.main(new String[0]);

        // 9. Launch Unified Real-Time Engine
        System.out.println("\n[MODULE 9] Launching Unified Quant Execution Engine...");
        com.sbk.optionspricer.core.MmapStatePublisher publisher = new com.sbk.optionspricer.core.MmapStatePublisher();
        com.sbk.optionspricer.core.UnifiedQuantEngine engine = new com.sbk.optionspricer.core.UnifiedQuantEngine(publisher);
        com.sbk.optionspricer.core.QuantSimulationHarness harness = new com.sbk.optionspricer.core.QuantSimulationHarness(engine);
        harness.start();

        System.out.println("\n[SUCCESS] UNIFIED SYSTEM ONLINE AND PROCESSING REAL-TIME MMAP IPC STATE.");
    }
}
