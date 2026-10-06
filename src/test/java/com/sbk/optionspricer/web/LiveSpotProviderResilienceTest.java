package com.sbk.optionspricer.web;

import com.sbk.optionspricer.market.MarketDataStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Providers fail, throttle and slow down; the platform must back off, not hammer, and not block callers. */
class LiveSpotProviderResilienceTest {

    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());

    private LiveSpotProvider provider(String fh, String pg, String av, String ms, LiveSpotProvider.HttpGetter http) {
        return new LiveSpotProvider(fh, pg, av, ms, http, clock::get, logs::add);
    }

    private String finnhubBody(double price) {
        return "{\"c\":" + price + ",\"t\":" + clock.get() / 1000 + "}";
    }

    @Test
    void aFailingSourceIsBackedOffInsteadOfCalledOnEveryRequest() {
        AtomicInteger calls = new AtomicInteger();
        LiveSpotProvider provider = provider("fh", null, null, null, (url, headers) -> {
            calls.incrementAndGet();
            throw new IOException("connection reset");
        });

        for (int second = 0; second < 20; second++) {
            provider.getQuote("SPY");
            clock.addAndGet(1_000L);
        }

        assertTrue(calls.get() <= 4, "20 seconds of requests against a dead source made " + calls.get() + " calls");
        assertTrue(calls.get() >= 2, "it must still be retried as the backoff elapses");
    }

    @Test
    void aSuccessfulCallClearsTheBackoff() {
        AtomicInteger mode = new AtomicInteger(0); // 0 = fail, 1 = succeed
        LiveSpotProvider provider = provider("fh", null, null, null, (url, headers) -> {
            if (mode.get() == 0) throw new IOException("down");
            return finnhubBody(100.0);
        });

        provider.getQuote("SPY");           // fails -> backoff
        clock.addAndGet(60_000L);           // backoff elapsed
        mode.set(1);
        assertEquals(100.0, provider.getQuote("SPY").last(), 1e-9);

        clock.addAndGet(30_000L);           // past the 20s cache lifetime, no backoff should remain
        assertEquals(100.0, provider.getQuote("SPY").last(), 1e-9);
    }

    @Test
    void http429CoolsASourceDownAndTheNextSourceServesTheQuote() {
        AtomicInteger finnhubCalls = new AtomicInteger();
        String avBody = "{\"Global Quote\":{\"05. price\":\"450.00\",\"07. latest trading day\":\"2024-01-05\"}}";
        LiveSpotProvider provider = provider("fh", null, "av", null, (url, headers) -> {
            if (url.contains("finnhub")) {
                finnhubCalls.incrementAndGet();
                throw new LiveSpotProvider.HttpStatusException(429, 0);
            }
            return avBody;
        });

        LiveSpotProvider.Quote first = provider.getQuote("SPY");
        assertEquals("ALPHA_VANTAGE", first.source(), "the next source must serve the quote");

        for (int i = 0; i < 2; i++) {
            clock.addAndGet(20_000L); // 40s total: new symbols (no cache) but still inside the 60s cool-down
            provider.getQuote("QQQ" + i);
        }
        assertEquals(1, finnhubCalls.get(), "a rate-limited source must be left alone while it cools down");
    }

    @Test
    void anAlphaVantageRateLimitNoticeCoolsItDown() {
        AtomicInteger calls = new AtomicInteger();
        LiveSpotProvider provider = provider(null, null, "av", null, (url, headers) -> {
            calls.incrementAndGet();
            return "{\"Note\":\"Thank you! Our standard API call frequency is 5 calls per minute.\"}";
        });

        provider.getQuote("SPY");
        for (int i = 0; i < 5; i++) {
            clock.addAndGet(10_000L);
            provider.getQuote("SPY");
        }

        assertEquals(1, calls.get(), "after a rate-limit notice the source must not be called again right away");
    }

    @Test
    void eachSourceStaysInsideItsCallBudget() {
        AtomicInteger calls = new AtomicInteger();
        LiveSpotProvider provider = provider(null, "pg", null, null, (url, headers) -> {
            calls.incrementAndGet();
            return "{\"ticker\":{\"lastQuote\":{\"p\":100.0,\"P\":100.2,\"t\":" + clock.get() * 1_000_000L + "}}}";
        });

        // Distinct symbols defeat the cache; Polygon's free plan allows 5 calls a minute.
        for (int i = 0; i < 12; i++) {
            provider.getQuote("S" + i);
        }

        assertEquals(5, calls.get(), "calls within one minute must not exceed the source's budget");

        clock.addAndGet(61_000L);
        provider.getQuote("LATER");
        assertEquals(6, calls.get(), "the budget refills as the window slides");
    }

    @Test
    void endOfDaySourcesAreCachedForHoursNotSeconds() {
        AtomicInteger calls = new AtomicInteger();
        String body = "{\"data\":[{\"close\":450.5,\"date\":\"2024-01-05T00:00:00+0000\"}]}";
        LiveSpotProvider provider = provider(null, null, null, "ms", (url, headers) -> {
            calls.incrementAndGet();
            return body;
        });

        provider.getQuote("SPY");
        clock.addAndGet(3_600_000L); // one hour later
        provider.getQuote("SPY");

        assertEquals(1, calls.get(), "a daily bar does not change within the hour; refetching only burns the monthly quota");
    }

    @Test
    void anExpiredQuoteIsServedImmediatelyWhileItRefreshesInTheBackground() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        LiveSpotProvider provider = provider("fh", null, null, null, (url, headers) -> {
            int n = calls.incrementAndGet();
            if (n == 1) return finnhubBody(100.0);
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return finnhubBody(105.0);
        });
        assertEquals(100.0, provider.getQuote("SPY").last(), 1e-9);

        clock.addAndGet(25_000L); // past the 20s lifetime; the refresh will block on the latch
        long started = System.nanoTime();
        LiveSpotProvider.Quote served = provider.getQuote("SPY");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(100.0, served.last(), 1e-9, "the previous quote is served while the refresh runs");
        assertTrue(elapsedMs < 1_000, "the caller must not wait for the network: " + elapsedMs + "ms");

        release.countDown();
        double last = served.last();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (last != 105.0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
            last = provider.getQuote("SPY").last();
        }
        assertEquals(105.0, last, 1e-9, "the background refresh must land");
    }

    @Test
    void concurrentRequestsForOneSymbolShareASingleRefresh() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        LiveSpotProvider provider = provider("fh", null, null, null, (url, headers) -> {
            calls.incrementAndGet();
            if (calls.get() > 1) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return finnhubBody(100.0);
        });
        provider.getQuote("SPY");
        clock.addAndGet(25_000L);

        for (int i = 0; i < 50; i++) {
            provider.getQuote("SPY");
        }
        release.countDown();
        Thread.sleep(200);

        assertEquals(2, calls.get(), "50 callers must not start 50 refreshes");
    }

    @Test
    void failuresAreLoggedWithoutCredentialsAndNotOnEveryRepeat() {
        LiveSpotProvider provider = provider(null, null, "av-key-SECRET123", null, (url, headers) -> {
            throw new IOException("failed calling " + url);
        });

        for (int i = 0; i < 30; i++) {
            provider.getQuote("SPY");
            clock.addAndGet(5_000L);
        }

        assertFalse(logs.isEmpty(), "a failing source must be reported somewhere");
        assertTrue(logs.stream().noneMatch(l -> l.contains("av-key-SECRET123")), "credentials must never be logged: " + logs);
        assertTrue(logs.size() <= 4, "150 seconds of failures should log a handful of lines, not " + logs.size());
        assertTrue(logs.stream().anyMatch(l -> l.contains("ALPHA_VANTAGE")), logs.toString());
    }

    @Test
    void noSourcesMeansUnavailableWithoutAnyNetworkCall() {
        AtomicInteger calls = new AtomicInteger();
        LiveSpotProvider provider = provider(null, null, null, null, (url, headers) -> {
            calls.incrementAndGet();
            return "{}";
        });

        assertEquals(MarketDataStatus.UNAVAILABLE, provider.getQuote("SPY").status());
        assertEquals(0, calls.get());
    }
}
