package com.sbk.optionspricer.metrics;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** How long each part of the engine takes, so a slow component can be seen instead of guessed at. */
class LatencyTrackerTest {

    private static final long MS = 1_000_000L;

    @Test
    void anEmptyTrackerHasNothingToReport() {
        assertTrue(new LatencyTracker().snapshot().isEmpty());
    }

    @Test
    void itReportsCountMeanPercentilesAndMaxPerComponentInMilliseconds() {
        LatencyTracker tracker = new LatencyTracker();
        for (int i = 1; i <= 100; i++) {
            tracker.record("surface.fit", i * MS);
        }
        tracker.record("order.submit", 7 * MS);

        Map<String, LatencyTracker.Stats> snapshot = tracker.snapshot();

        LatencyTracker.Stats fit = snapshot.get("surface.fit");
        assertEquals(100, fit.count());
        assertEquals(50.5, fit.meanMs(), 1e-9);
        assertEquals(50.0, fit.p50Ms(), 1.0);
        assertEquals(95.0, fit.p95Ms(), 1.0);
        assertEquals(100.0, fit.maxMs(), 1e-9);
        assertEquals(100.0, fit.lastMs(), 1e-9);
        assertEquals(7.0, snapshot.get("order.submit").p50Ms(), 1e-9);
    }

    @Test
    void thePercentilesLookAtTheRecentSamplesOnlyWhileCountAndMaxCoverEverything() {
        LatencyTracker tracker = new LatencyTracker(10);
        tracker.record("x", 1_000 * MS); // one slow outlier, then ten fast calls push it out of the window
        for (int i = 0; i < 10; i++) {
            tracker.record("x", 2 * MS);
        }

        LatencyTracker.Stats stats = tracker.snapshot().get("x");

        assertEquals(11, stats.count());
        assertEquals(1_000.0, stats.maxMs(), 1e-9, "the worst call ever is not forgotten");
        assertEquals(2.0, stats.p95Ms(), 1e-9, "but the recent percentiles describe the recent calls");
    }

    @Test
    void timingACallRecordsItEvenWhenItThrowsAndPassesTheResultThrough() {
        LatencyTracker tracker = new LatencyTracker();

        assertEquals("ok", tracker.time("a", () -> "ok"));
        assertThrows(IllegalStateException.class, () -> tracker.time("a", () -> { throw new IllegalStateException("boom"); }));
        AtomicInteger ran = new AtomicInteger();
        tracker.time("b", (Runnable) ran::incrementAndGet);

        assertEquals(2, tracker.snapshot().get("a").count(), "a failed call is still a call that took time");
        assertEquals(1, ran.get());
        assertEquals(1, tracker.snapshot().get("b").count());
    }

    @Test
    void concurrentRecordingLosesNothing() throws Exception {
        LatencyTracker tracker = new LatencyTracker();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 5_000; i++) tracker.record("c", MS);
            });
            threads[t].start();
        }
        for (Thread thread : threads) thread.join();

        assertEquals(20_000, tracker.snapshot().get("c").count());
    }

    @Test
    void theSnapshotIsPlainValuesForJson() {
        LatencyTracker tracker = new LatencyTracker();
        tracker.record("x", 3 * MS);

        Map<String, Object> json = tracker.toJson();

        @SuppressWarnings("unchecked")
        Map<String, Object> x = (Map<String, Object>) json.get("x");
        assertEquals(1L, x.get("count"));
        assertEquals(3.0, (Double) x.get("p50Ms"), 1e-9);
        assertTrue(x.containsKey("p95Ms") && x.containsKey("maxMs") && x.containsKey("meanMs") && x.containsKey("lastMs"));
    }

    @Test
    void aNegativeDurationOrNoNameIsIgnoredNotRecorded() {
        LatencyTracker tracker = new LatencyTracker();
        tracker.record("x", -5);
        tracker.record(null, MS);

        assertTrue(tracker.snapshot().isEmpty());
    }
}
