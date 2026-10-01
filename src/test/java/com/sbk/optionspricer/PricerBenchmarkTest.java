package com.sbk.optionspricer;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class PricerBenchmarkTest {

    private double[] strikes;
    private double[] outPrices;
    
    @Setup(Level.Trial)
    public void setup() {
        strikes = new double[1024];
        outPrices = new double[1024];
        for (int i = 0; i < 1024; i++) {
            strikes[i] = 50.0 + i * 0.1;
        }
    }

    @Benchmark
    public void benchmarkScalar(Blackhole bh) {
        for (int i = 0; i < strikes.length; i++) {
            outPrices[i] = BlackScholesPricer.price(OptionType.CALL, 100.0, strikes[i], 1.0, 0.05, 0.20, 0.0);
        }
        bh.consume(outPrices);
    }

    @Benchmark
    public void benchmarkVectorBatch(Blackhole bh) {
        VectorBlackScholesPricer.priceBatchVectorized(100.0, strikes, 1.0, 0.05, 0.20, true, outPrices);
        bh.consume(outPrices);
    }

}
