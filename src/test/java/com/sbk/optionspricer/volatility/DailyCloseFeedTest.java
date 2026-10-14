package com.sbk.optionspricer.volatility;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DailyCloseFeedTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T15:00:00Z"), ZoneOffset.UTC);

    private static List<DailyCloseFeed.DailyClose> days(int count, LocalDate lastDate) {
        List<DailyCloseFeed.DailyClose> out = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            out.add(new DailyCloseFeed.DailyClose(lastDate.minusDays(i), 500.0 + (count - i)));
        }
        return out;
    }

    @Test
    void loadsTheClosesButDropsToday() {
        // today's bar is still being built while the market is open: its "close" is just the latest print
        RealizedVolForecast forecast = new RealizedVolForecast(RealizedVolForecast.Model.EWMA);
        List<DailyCloseFeed.DailyClose> bars = days(30, LocalDate.of(2026, 10, 14)); // the last one is today
        DailyCloseFeed feed = new DailyCloseFeed(forecast, start -> bars, CLOCK, Duration.ofHours(6));

        assertTrue(feed.refreshOnce());

        assertEquals(29, forecast.historySize(), "30 bars minus today's");
    }

    @Test
    void aFailedLoadLeavesWhatWasThereAndSaysSoInsteadOfThrowing() {
        RealizedVolForecast forecast = new RealizedVolForecast(RealizedVolForecast.Model.EWMA);
        forecast.setPrices(new double[]{500, 501, 502});
        DailyCloseFeed feed = new DailyCloseFeed(forecast, start -> { throw new IllegalStateException("no route to host"); },
                CLOCK, Duration.ofHours(6));

        assertFalse(feed.refreshOnce());

        assertEquals(3, forecast.historySize());
        assertTrue(feed.lastProblem().contains("no route to host"), feed.lastProblem());
    }

    @Test
    void anAlmostEmptySeriesIsNotLoadedOverAGoodOne() {
        RealizedVolForecast forecast = new RealizedVolForecast(RealizedVolForecast.Model.EWMA);
        forecast.setPrices(new double[]{500, 501, 502, 503, 504});
        DailyCloseFeed feed = new DailyCloseFeed(forecast, start -> days(2, LocalDate.of(2026, 10, 13)), CLOCK, Duration.ofHours(6));

        assertFalse(feed.refreshOnce());

        assertEquals(5, forecast.historySize());
    }
}
