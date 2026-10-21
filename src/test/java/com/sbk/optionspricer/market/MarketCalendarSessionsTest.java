package com.sbk.optionspricer.market;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** The calendar a long-running engine relies on: any year, early closes, and the next open from before the bell. */
class MarketCalendarSessionsTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private final MarketCalendar calendar = new MarketCalendar();

    private static Instant at(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, NY).toInstant();
    }

    @Test
    void holidaysCoverTheYearsBeforeAndAfterTheOneTheEngineStartedIn() {
        assertTrue(calendar.isHoliday(LocalDate.of(2027, 1, 18)), "MLK day next January: an engine started in 2026 is still running");
        assertTrue(calendar.isHoliday(LocalDate.of(2027, 3, 26)), "Good Friday 2027");
        assertTrue(calendar.isHoliday(LocalDate.of(2025, 12, 25)));
        assertTrue(calendar.isMarketClosed(at(2027, 1, 18, 11, 0)));
    }

    @Test
    void earlyClosesEndTheSessionAtOne() {
        assertEquals(LocalTime.of(13, 0), calendar.closeTime(LocalDate.of(2026, 11, 27)), "the day after Thanksgiving");
        assertEquals(LocalTime.of(13, 0), calendar.closeTime(LocalDate.of(2026, 12, 24)), "Christmas Eve on a weekday");
        assertEquals(LocalTime.of(13, 0), calendar.closeTime(LocalDate.of(2025, 7, 3)), "the day before Independence Day on a Thursday");
        assertEquals(LocalTime.of(16, 0), calendar.closeTime(LocalDate.of(2026, 10, 20)), "an ordinary day");

        assertTrue(calendar.isMarketOpen(at(2026, 11, 27, 12, 59)));
        assertTrue(calendar.isMarketClosed(at(2026, 11, 27, 13, 0)));
        assertTrue(calendar.isMarketClosed(at(2026, 11, 27, 14, 30)), "an hour and a half after an early close is closed, not open");
    }

    @Test
    void theNextOpenFromBeforeTheBellIsToday() {
        assertEquals(at(2026, 10, 20, 9, 30), calendar.nextMarketOpen(at(2026, 10, 20, 8, 0)));
        assertEquals(at(2026, 10, 21, 9, 30), calendar.nextMarketOpen(at(2026, 10, 20, 17, 0)));
        assertEquals(at(2026, 10, 26, 9, 30), calendar.nextMarketOpen(at(2026, 10, 23, 17, 0)), "Friday evening to Monday");
        assertEquals(at(2026, 11, 27, 9, 30), calendar.nextMarketOpen(at(2026, 11, 25, 17, 0)), "over Thanksgiving");
    }

    @Test
    void theNextCloseUsesTheEarlyCloseAndTheSameDayWhenOpen() {
        assertEquals(at(2026, 10, 20, 16, 0), calendar.nextMarketClose(at(2026, 10, 20, 11, 0)));
        assertEquals(at(2026, 11, 27, 13, 0), calendar.nextMarketClose(at(2026, 11, 25, 17, 0)));
        assertEquals(at(2026, 10, 21, 16, 0), calendar.nextMarketClose(at(2026, 10, 20, 17, 0)));
    }

    @Test
    void theHolidayAfterASaturdayJulyFourthIsObservedOnFridayWithNoEarlyCloseBefore() {
        assertTrue(calendar.isHoliday(LocalDate.of(2026, 7, 3)));
        assertEquals(LocalTime.of(16, 0), calendar.closeTime(LocalDate.of(2026, 7, 2)));
    }
}
