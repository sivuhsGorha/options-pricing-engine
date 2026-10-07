package com.sbk.optionspricer.tuning;

import com.sbk.optionspricer.BlackScholesPricer;
import com.sbk.optionspricer.ImpliedVolatilitySolver;
import com.sbk.optionspricer.NormalDistribution;
import com.sbk.optionspricer.OptionParameters;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.core.OrderBookTick;
import com.sbk.optionspricer.models.pde.DiscreteDividendPricer;

/**
 * Runs the pricing and tick-handling code paths a few thousand times so the JVM has already interpreted and
 * profiled them before the first real request.
 *
 * <p>This shortens the cold-start penalty; it does not guarantee the C2 compiler has finished. (An earlier
 * version only exercised tick getters and setters, so the pricers it claimed to warm were never touched.)
 * Nothing calls it automatically: invoke it at startup if the first-request latency matters.
 */
public class JitCompilerWarmer {

    private static volatile double checksum;

    /** Runs the warm-up and returns how long it took in milliseconds. */
    public static long warmUp() {
        System.out.println("[JVM TUNING] Warming pricing and tick paths...");
        long start = System.nanoTime();
        double sink = 0.0;

        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            OrderBookTick tick = new OrderBookTick();
            tick.wrap(arena.allocate(40));

            for (int i = 0; i < 20_000; i++) {
                double spot = 90.0 + (i % 21);
                double strike = 95.0 + (i % 11);
                double expiry = 0.1 + (i % 7) * 0.2;
                double vol = 0.15 + (i % 5) * 0.05;
                OptionType type = (i & 1) == 0 ? OptionType.CALL : OptionType.PUT;

                double price = BlackScholesPricer.price(type, spot, strike, expiry, 0.03, vol, 0.01);
                sink += price;
                sink += BlackScholesPricer.greeks(type, new OptionParameters(spot, strike, expiry, 0.03, vol, 0.01)).delta();
                sink += NormalDistribution.cdf((spot - strike) / 10.0);
                if (price > 1e-6) {
                    sink += ImpliedVolatilitySolver.solve(type, spot, strike, expiry, 0.03, 0.01, price, new double[5]).orElse(0.0);
                }

                tick.setBidPrice(spot - 0.01);
                tick.setAskPrice(spot + 0.01);
                tick.setInstrumentId(i);
                sink += (tick.getBidPrice() + tick.getAskPrice()) / 2.0;
            }
            for (int i = 0; i < 40; i++) {
                sink += DiscreteDividendPricer.price(OptionType.PUT,
                        new OptionParameters(100.0, 95.0 + i, 0.5, 0.03, 0.25, 0.0), null, 80, 80, true);
            }
        }

        checksum = sink; // keeps the work observable so the JIT cannot discard it as dead code
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("[JVM TUNING] Warm-up finished in " + elapsedMs + " ms.");
        return elapsedMs;
    }

    /** Sum of everything the last warm-up computed; finite and non-zero when the pricers really ran. */
    public static double lastChecksum() {
        return checksum;
    }
}
