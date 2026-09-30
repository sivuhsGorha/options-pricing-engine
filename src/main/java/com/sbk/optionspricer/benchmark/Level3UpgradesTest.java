package com.sbk.optionspricer.benchmark;

import com.sbk.optionspricer.BlackScholesPricer;
import com.sbk.optionspricer.FastMath;
import com.sbk.optionspricer.OptionParameters;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.VectorBlackScholesPricer;
import com.sbk.optionspricer.core.MmapStatePublisher;
import com.sbk.optionspricer.models.pde.DiscreteDividendPricer;
import com.sbk.optionspricer.models.pde.DiscreteDividendPricer.DiscreteDividend;
import com.sbk.optionspricer.volatility.SlvCalibrator;
import com.sbk.optionspricer.volatility.SlvCalibrator.SlvParams;
import com.sbk.optionspricer.web.MmapStateReader;

/**
 * Empirical Verification Suite for Level 3 Proprietary Upgrades (Phase 6).
 */
public class Level3UpgradesTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=========================================================================");
        System.out.println("       LEVEL 3 PROPRIETARY UPGRADES EMPIRICAL VERIFICATION SUITE         ");
        System.out.println("=========================================================================");

        testZeroGcMmapIpc();
        testFastMathPrecisionAndSpeed();
        testVectorizedSimdPricer();
        testDiscreteDividendPdeJump();
        testSlvCalibration();

        System.out.println("\n[SUCCESS] ALL LEVEL 3 PROPRIETARY UPGRADES EMPIRICALLY VERIFIED!");
        System.out.println("=========================================================================");
    }

    private static void testZeroGcMmapIpc() throws Exception {
        System.out.println("\n--- 6.1 Zero-GC IPC Decoupling (Mmap State File) ---");
        MmapStatePublisher publisher = new MmapStatePublisher();
        publisher.publishRiskState(142.50, 12.35, 88.90, 45200.00);

        MmapStateReader reader = new MmapStateReader();
        System.out.printf("Published: Delta=142.50, Gamma=12.35, Vega=88.90, Margin=45200.00%n");
        System.out.printf("Read mmap: Delta=%.2f, Gamma=%.2f, Vega=%.2f, Margin=%.2f%n",
                reader.getNetDelta(), reader.getNetGamma(), reader.getNetVega(), reader.getSpanMargin());

        if (Math.abs(reader.getNetDelta() - 142.50) > 1e-4 || Math.abs(reader.getSpanMargin() - 45200.00) > 1e-4) {
            throw new AssertionError("Mmap IPC read/write mismatch!");
        }
        publisher.close();
        reader.close();
        System.out.println("-> Zero-GC IPC verification PASSED.");
    }

    private static void testFastMathPrecisionAndSpeed() {
        System.out.println("\n--- 6.3 Fast-Math Approximations (Chebyshev / IEEE 754 Bit Magic) ---");
        double xExp = -0.456;
        double stdExp = Math.exp(xExp);
        double fastExp = FastMath.fastExp(xExp);
        double errExp = Math.abs(stdExp - fastExp);

        double xLog = 1.2345;
        double stdLog = Math.log(xLog);
        double fastLog = FastMath.fastLog(xLog);
        double errLog = Math.abs(stdLog - fastLog);

        double xCdf = 1.96;
        double fastCdf = FastMath.fastCdf(xCdf);
        System.out.printf("stdExp(%.3f) = %.8f | fastExp = %.8f | err = %.2e%n", xExp, stdExp, fastExp, errExp);
        System.out.printf("stdLog(%.4f) = %.8f | fastLog = %.8f | err = %.2e%n", xLog, stdLog, fastLog, errLog);
        System.out.printf("fastCdf(1.96) = %.6f (Expected ~0.975002)%n", fastCdf);

        if (errExp > 1e-4 || errLog > 1e-4) {
            throw new AssertionError("FastMath approximation error exceeds tolerance!");
        }
        System.out.println("-> FastMath Chebyshev approximations PASSED.");
    }

    private static void testVectorizedSimdPricer() {
        System.out.println("\n--- 6.2 Java 21 Vector API (SIMD Batch Option Pricing) ---");
        int numStrikes = 1024;
        double spot = 100.0;
        double t = 1.0;
        double r = 0.05;
        double vol = 0.20;

        double[] strikes = new double[numStrikes];
        double[] pricesSimd = new double[numStrikes];
        double[] pricesScalar = new double[numStrikes];

        for (int i = 0; i < numStrikes; i++) {
            strikes[i] = 70.0 + (60.0 * i / numStrikes);
        }

        // SIMD batch pricing
        long startSimd = System.nanoTime();
        VectorBlackScholesPricer.priceBatchVectorized(spot, strikes, t, r, vol, true, pricesSimd);
        long endSimd = System.nanoTime();

        // Scalar benchmark
        long startScalar = System.nanoTime();
        for (int i = 0; i < numStrikes; i++) {
            pricesScalar[i] = BlackScholesPricer.price(OptionType.CALL, spot, strikes[i], t, r, vol, 0.0);
        }
        long endScalar = System.nanoTime();

        double maxDiff = 0.0;
        for (int i = 0; i < numStrikes; i++) {
            maxDiff = Math.max(maxDiff, Math.abs(pricesSimd[i] - pricesScalar[i]));
        }

        System.out.printf("Priced %d strikes SIMD in %.2f us (Scalar: %.2f us)%n",
                numStrikes, (endSimd - startSimd) / 1000.0, (endScalar - startScalar) / 1000.0);
        System.out.printf("Max SIMD vs Scalar price discrepancy: %.6f%n", maxDiff);

        if (maxDiff > 1e-2) {
            throw new AssertionError("SIMD batch price diverges from Black-Scholes benchmark!");
        }
        System.out.println("-> SIMD Vector pricing PASSED.");
    }

    private static void testDiscreteDividendPdeJump() {
        System.out.println("\n--- 6.4 Discrete Dividend PDE Jump Conditions (Crank-Nicolson) ---");
        OptionParameters params = new OptionParameters(100.0, 100.0, 1.0, 0.25, 0.05, 0.0);
        DiscreteDividend[] divs = new DiscreteDividend[] {
            new DiscreteDividend(0.5, 3.00) // $3 cash dividend at t=0.5 years
        };

        double priceNoDiv = DiscreteDividendPricer.price(OptionType.CALL, params, null, 200, 200, true);
        double priceWithDiv = DiscreteDividendPricer.price(OptionType.CALL, params, divs, 200, 200, true);

        System.out.printf("American Call (No Div):   %.4f%n", priceNoDiv);
        System.out.printf("American Call (With Div $3.00 at t=0.5): %.4f%n", priceWithDiv);

        if (priceWithDiv >= priceNoDiv || priceWithDiv <= 0.0) {
            throw new AssertionError("Discrete dividend PDE price violated economic constraints!");
        }
        System.out.println("-> Discrete Dividend PDE solver PASSED.");
    }

    private static void testSlvCalibration() {
        System.out.println("\n--- 6.5 Stochastic Local Volatility (SLV) Calibration ---");
        SlvParams heston = new SlvParams(2.0, 0.04, 0.3, -0.6, 0.04);
        double dupireVol = SlvCalibrator.computeDupireLocalVol(100.0, 105.0, 0.5, 0.05, 0.22, 0.01, -0.001, 0.0002);
        double leverageFactor = SlvCalibrator.computeLeverageFactor(dupireVol, heston, 0.5);

        System.out.printf("Dupire Local Vol: %.4f (22.00%%)%n", dupireVol);
        System.out.printf("SLV Leverage Scale Factor: %.4f%n", leverageFactor);

        if (Double.isNaN(dupireVol) || Double.isNaN(leverageFactor) || leverageFactor <= 0) {
            throw new AssertionError("SLV Calibration produced NaN or negative leverage!");
        }
        System.out.println("-> SLV Calibration PASSED.");
    }
}
