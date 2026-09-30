package com.sbk.optionspricer;

/**
 * Demonstrates the full pricing engine:
 *   1. Black-Scholes price + Greeks for a sample option
 *   2. Monte Carlo cross-check against the closed-form price
 *   3. Implied volatility solved back out from a known price
 *   4. A real, measured timing benchmark (not an asserted number —
 *      actually measured on whatever machine this runs on)
 */
public class Main {

    public static void main(String[] args) {
        OptionParameters params = OptionParameters.noDividend(
                100.0,  // spot
                105.0,  // strike
                0.5,    // 6 months to expiry
                0.05,   // 5% risk-free rate
                0.25    // 25% annualized volatility
        );

        System.out.println("=== Black-Scholes Pricing ===");
        double callPrice = BlackScholesPricer.price(OptionType.CALL, params);
        double putPrice = BlackScholesPricer.price(OptionType.PUT, params);
        System.out.printf("Call price: %.4f%n", callPrice);
        System.out.printf("Put price:  %.4f%n", putPrice);

        // Put-call parity sanity check: C - P should equal S*e^-qT - K*e^-rT.
        // This is a free correctness check that costs nothing to include.
        double parityLeft = callPrice - putPrice;
        double parityRight = params.spot() - params.strike() * Math.exp(-params.riskFreeRate() * params.timeToExpiry());
        System.out.printf("Put-call parity check: %.6f vs %.6f (should match)%n", parityLeft, parityRight);

        System.out.println("\n=== Greeks (Call) ===");
        Greeks callGreeks = BlackScholesPricer.greeks(OptionType.CALL, params);
        System.out.printf("Delta: %.4f%n", callGreeks.delta());
        System.out.printf("Gamma: %.4f%n", callGreeks.gamma());
        System.out.printf("Vega (per 1%% vol):  %.4f%n", callGreeks.vega() / 100);
        System.out.printf("Theta (per day):    %.4f%n", callGreeks.theta() / 365);
        System.out.printf("Rho (per 1%% rate):  %.4f%n", callGreeks.rho() / 100);

        System.out.println("\n=== Monte Carlo Cross-Check (Call) ===");
        MonteCarloPricer.PricingResult mcResult = MonteCarloPricer.price(OptionType.CALL, params, 500_000, 42L);
        System.out.printf("Monte Carlo price: %.4f (+/- %.4f, 95%% CI)%n", mcResult.price(), mcResult.confidenceInterval95());
        System.out.printf("Black-Scholes price: %.4f%n", callPrice);
        double diff = Math.abs(mcResult.price() - callPrice);
        boolean withinCI = diff <= mcResult.confidenceInterval95();
        System.out.printf("Difference: %.4f — %s%n", diff,
                withinCI ? "within simulation error, consistent" : "OUTSIDE simulation error, investigate");

        System.out.println("\n=== Implied Volatility Solve ===");
        System.out.printf("Known call price %.4f was generated with %.1f%% volatility.%n",
                callPrice, params.volatility() * 100);
        double solvedVol = ImpliedVolatilitySolver.solve(OptionType.CALL, params, callPrice);
        System.out.printf("Solver recovered: %.4f%% volatility%n", solvedVol * 100);

        System.out.println("\n=== Timing Benchmark (measured, not asserted) ===");
        int iterations = 100_000;
        long start = System.nanoTime();
        double checksum = 0; // prevents the JIT from optimizing the loop away entirely
        for (int i = 0; i < iterations; i++) {
            checksum += BlackScholesPricer.price(OptionType.CALL, params);
        }
        long elapsedNanos = System.nanoTime() - start;
        double avgMicros = (elapsedNanos / 1000.0) / iterations;
        System.out.printf("Priced %,d options in %.2f ms total (avg %.3f microseconds/option)%n",
                iterations, elapsedNanos / 1_000_000.0, avgMicros);
        System.out.printf("(checksum %.2f — ignore, just prevents dead-code elimination)%n", checksum);
        System.out.println("Note: this is a single-threaded, cold-JVM measurement on whatever");
        System.out.println("machine runs it — it's real, but it's not a benchmark against a");
        System.out.println("production system, and JIT warm-up means a longer run would show");
        System.out.println("a faster steady-state number than this one.");
    }
}
