package com.sbk.optionspricer;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import com.sbk.optionspricer.models.pde.DiscreteDividendPricer;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
public class PricerBenchmarkTest {

    private double[] batch1024;
    private double[] batch10000;
    
    private DiscreteDividendPricer.Workspace pdeWorkspace;

    @Setup(Level.Trial)
    public void setup() {
        batch1024 = new double[1024];
        for (int i = 0; i < 1024; i++) {
            batch1024[i] = 50.0 + i * 0.1;
        }

        batch10000 = new double[10000];
        for (int i = 0; i < 10000; i++) {
            batch10000[i] = 50.0 + i * 0.01;
        }

        pdeWorkspace = new DiscreteDividendPricer.Workspace(150);

        // Verify equality before timing
        double[] outScalar = new double[1024];
        for (int i = 0; i < batch1024.length; i++) {
            outScalar[i] = BlackScholesPricer.price(OptionType.CALL, 100.0, batch1024[i], 1.0, 0.05, 0.20, 0.0);
        }
        double[] outVector = VectorBlackScholesPricer.priceBatchSimdSingleThread(100.0, batch1024, 1.0, 0.05, 0.20, true);
        
        for (int i = 0; i < 1024; i++) {
            if (Math.abs(outScalar[i] - outVector[i]) > 1e-9) {
                throw new AssertionError("Vector results do not match scalar results!");
            }
        }

        // CDF Error analysis
        double maxAbsErr = 0;
        double maxRelErr = 0;
        double[] testPoints = {-5.0, -3.0, -1.0, 0.0, 1.0, 3.0, 5.0};
        jdk.incubator.vector.DoubleVector vPoints = jdk.incubator.vector.DoubleVector.fromArray(jdk.incubator.vector.DoubleVector.SPECIES_PREFERRED, testPoints, 0);
        jdk.incubator.vector.DoubleVector vCdfs = VectorBlackScholesPricer.vectorCdf(vPoints);
        double[] cdfs = new double[testPoints.length];
        vCdfs.intoArray(cdfs, 0);
        
        for (int i = 0; i < testPoints.length; i++) {
            double expected = NormalDistribution.cdf(testPoints[i]);
            double actual = cdfs[i];
            double absErr = Math.abs(expected - actual);
            double relErr = expected == 0 ? 0 : Math.abs(absErr / expected);
            maxAbsErr = Math.max(maxAbsErr, absErr);
            maxRelErr = Math.max(maxRelErr, relErr);
        }
        System.out.println("CDF Max Abs Error: " + maxAbsErr);
        System.out.println("CDF Max Rel Error: " + maxRelErr);
    }

    // 1 op = 1 option priced
    @Benchmark
    public void benchmarkBlackScholesSingle(Blackhole bh) {
        bh.consume(BlackScholesPricer.price(OptionType.CALL, 100.0, 100.0, 1.0, 0.05, 0.20, 0.0));
    }

    // 1 op = 10000 options priced sequentially with preallocated array
    @Benchmark
    public void benchmarkBlackScholesBatch10000Preallocated(Blackhole bh) {
        double[] outPrices = new double[10000];
        VectorBlackScholesPricer.priceBatchPreallocated(100.0, batch10000, 1.0, 0.05, 0.20, true, outPrices);
        bh.consume(outPrices);
    }
    @Benchmark
    public void benchmarkBlackScholesBatch10000(Blackhole bh) {
        double[] outPrices = new double[batch10000.length];
        for (int i = 0; i < batch10000.length; i++) {
            outPrices[i] = BlackScholesPricer.price(OptionType.CALL, 100.0, batch10000[i], 1.0, 0.05, 0.20, 0.0);
        }
        bh.consume(outPrices);
    }

    // 1 op = 1024 options priced sequentially
    @Benchmark
    public void benchmarkBlackScholesBatch1024Scalar(Blackhole bh) {
        double[] outPrices = new double[batch1024.length];
        for (int i = 0; i < batch1024.length; i++) {
            outPrices[i] = BlackScholesPricer.price(OptionType.CALL, 100.0, batch1024[i], 1.0, 0.05, 0.20, 0.0);
        }
        bh.consume(outPrices);
    }

    // 1 op = 1024 options priced via VectorBlackScholesPricer (parallelStream)
    @Benchmark
    public void benchmarkBlackScholesBatch1024Parallel(Blackhole bh) {
        double[] outPrices = VectorBlackScholesPricer.priceBatchParallel(100.0, batch1024, 1.0, 0.05, 0.20, true);
        bh.consume(outPrices);
    }

    // 1 op = 1024 options priced via VectorBlackScholesPricer (SIMD vector prototype)
    @Benchmark
    public void benchmarkBlackScholesBatch1024SimdPrototype(Blackhole bh) {
        double[] outPrices = VectorBlackScholesPricer.priceBatchSimdSingleThread(100.0, batch1024, 1.0, 0.05, 0.20, true);
        bh.consume(outPrices);
    }

    // 1 op = 1 PDE solve (150 space steps, 150 time steps), American Call, 100 Spot, 100 Strike, 1 yr expiry, 5% rate, 20% vol, 0 dividends.
    // Allocates new arrays per solve.
    @Benchmark
    public void benchmarkPdeScalarAllocating(Blackhole bh) {
        OptionParameters p = new OptionParameters(100.0, 100.0, 1.0, 0.05, 0.20, 0.0);
        bh.consume(DiscreteDividendPricer.price(OptionType.CALL, p, null, 150, 150, true));
    }

    // 1 op = 1 PDE solve (150 space steps, 150 time steps), American Call, 100 Spot, 100 Strike, 1 yr expiry, 5% rate, 20% vol, 0 dividends.
    // Reuses a preallocated Workspace to achieve zero-allocation.
    @Benchmark
    public void benchmarkPdeScalarZeroAlloc(Blackhole bh) {
        bh.consume(DiscreteDividendPricer.priceWorkspace(OptionType.CALL, 100.0, 100.0, 1.0, 0.05, 0.20, null, 150, 150, true, pdeWorkspace));
    }
}
