package com.sbk.optionspricer.core;

import com.sbk.optionspricer.execution.ExecutionResult;
import com.sbk.optionspricer.execution.OrderManager;
import com.sbk.optionspricer.execution.PortfolioRiskAdmission;
import com.sbk.optionspricer.execution.PositionTracker;
import com.sbk.optionspricer.execution.PreTradeRiskFilter;
import com.sbk.optionspricer.execution.StrategyExecutionLoop;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.risk.FillRecorder;
import com.sbk.optionspricer.web.LiveSpotProvider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** A failing strategy must neither stop the engine tick nor flood the log. */
class HarnessResilienceTest {

    @Test
    void startupDoesNotWaitForTheMarketDataProvidersToAnswer() throws Exception {
        System.setProperty("MMAP_STATE_FILE", "target/harness_startup_state.dat");
        MmapStatePublisher publisher = new MmapStatePublisher() {
            @Override
            public void publishRiskState(double netDelta, double netGamma, double netVega, double scenarioMargin) {
            }
        };
        UnifiedQuantEngine engine = new UnifiedQuantEngine(publisher, code -> fail("engine must not exit: " + code));
        // Keys are configured and every provider call hangs for 3s: the status probe must not hold up startup.
        LiveSpotProvider slow = new LiveSpotProvider("fh", "pg", "av", "ms", (url, headers) -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new java.io.IOException("slow");
        });
        QuantSimulationHarness harness = new QuantSimulationHarness(engine, slow);

        long started = System.nanoTime();
        harness.start();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        harness.stop();

        assertTrue(elapsedMs < 1_500, "start() took " + elapsedMs + "ms: it blocked on provider network calls");
    }

    // Found on review (2026-10-10): the strategy step ran on the 10 ms engine tick, so an order waiting on the venue
    // (up to 15 s with the Alpaca transport) stopped risk publication and limit checks for as long as it waited.

    @Test
    void aStrategyStepWaitingOnTheVenueDoesNotStopTheEngineTickAndIsNeverEnteredTwice() throws Exception {
        System.setProperty("MMAP_STATE_FILE", "target/harness_blocking_state.dat");
        AtomicInteger publishes = new AtomicInteger();
        MmapStatePublisher publisher = new MmapStatePublisher() {
            @Override
            public void publishRiskState(double netDelta, double netGamma, double netVega, double scenarioMargin) {
                publishes.incrementAndGet();
            }
        };
        UnifiedQuantEngine engine = new UnifiedQuantEngine(publisher, code -> fail("engine must not exit: " + code));
        LiveSpotProvider offline = new LiveSpotProvider(null, null, null, null,
                (url, headers) -> { throw new java.io.IOException("offline test"); });
        QuantSimulationHarness harness = new QuantSimulationHarness(engine, offline);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger entered = new AtomicInteger();
        harness.setStrategyMode("vol_spread");
        harness.setOptionStrategy(() -> {
            entered.incrementAndGet();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, java.time.Duration.ofMillis(10));

        try {
            harness.start();
            Thread.sleep(150);
            int atFirst = publishes.get();
            Thread.sleep(600);
            int whileBlocked = publishes.get() - atFirst;
            assertEquals(1, entered.get(), "the step is due every 10 ms but must not be entered again while it is still running");
            assertTrue(whileBlocked >= 20, "the engine must keep publishing risk while a step waits on the venue; ticks=" + whileBlocked);
        } finally {
            release.countDown();
            harness.stop();
        }
    }

    @Test
    void theHarnessAsksTheProvidersForTheConfiguredSymbolNotAHardCodedOne() throws Exception {
        System.setProperty("MMAP_STATE_FILE", "target/harness_symbol_state.dat");
        MmapStatePublisher publisher = new MmapStatePublisher() {
            @Override
            public void publishRiskState(double netDelta, double netGamma, double netVega, double scenarioMargin) {
            }
        };
        UnifiedQuantEngine engine = new UnifiedQuantEngine(publisher, code -> fail("engine must not exit: " + code));
        java.util.List<String> urls = new java.util.concurrent.CopyOnWriteArrayList<>();
        LiveSpotProvider provider = new LiveSpotProvider("fh", "pg", "av", "ms", (url, headers) -> {
            urls.add(url);
            throw new java.io.IOException("offline test");
        });
        QuantSimulationHarness harness = new QuantSimulationHarness(engine, provider);
        harness.setSymbol("qqq");

        harness.start();
        Thread.sleep(400);
        harness.stop();

        assertFalse(urls.isEmpty(), "the harness asked the providers for a quote");
        assertTrue(urls.stream().anyMatch(u -> u.contains("QQQ")), urls.toString());
        assertTrue(urls.stream().noneMatch(u -> u.contains("SPY")), "no request names the old hard-coded symbol: " + urls);
    }

    @Test
    void failingStrategyKeepsTheEngineTickingAndLogsAreThrottled() throws Exception {
        System.setProperty("MMAP_STATE_FILE", "target/harness_resilience_state.dat");
        AtomicInteger publishes = new AtomicInteger();
        MmapStatePublisher publisher = new MmapStatePublisher() {
            @Override
            public void publishRiskState(double netDelta, double netGamma, double netVega, double scenarioMargin) {
                publishes.incrementAndGet();
            }
        };
        UnifiedQuantEngine engine = new UnifiedQuantEngine(publisher, code -> fail("engine must not exit: " + code));

        PositionTracker tracker = new PositionTracker(FillRecorder.NONE);
        OrderManager manager = new OrderManager(new PreTradeRiskFilter(1000, 1e12, 100_000),
                (order, sym, bid, ask) -> new ExecutionResult(sym, order.quantity(), order.price(), true, "Executed"), tracker);
        PortfolioRiskAdmission admission = new PortfolioRiskAdmission(1e12, 1e9, 1e9, 1e9, 1e9);
        StrategyExecutionLoop failing = new StrategyExecutionLoop("SPY", manager, admission, tracker, 10.0, 0.004) {
            @Override
            public ExecutionSummary onPrice(double price, MarketSnapshot context) {
                throw new IllegalStateException("strategy-boom");
            }
        };
        // No API keys: hermetic, no network.
        LiveSpotProvider offline = new LiveSpotProvider(null, null, null, null,
                (url, headers) -> { throw new java.io.IOException("offline test"); });
        QuantSimulationHarness harness = new QuantSimulationHarness(engine, offline, tracker, manager, admission, failing);

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try {
            harness.start();
            Thread.sleep(600);
            harness.stop();
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(UnifiedQuantEngine.EngineState.RUNNING, engine.getState());
        assertTrue(publishes.get() >= 20, "the engine must keep ticking while the strategy fails; ticks=" + publishes.get());
        long logged = captured.toString().lines().filter(l -> l.contains("strategy-boom")).count();
        assertTrue(logged >= 1, "the failure must be reported at least once");
        assertTrue(logged <= 3, "a failure repeating every tick must be throttled, but logged " + logged + " times");
    }
}
