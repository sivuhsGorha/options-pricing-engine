package com.sbk.optionspricer.market;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MarketCalendarTest {

    @Test
    void testMarketOpenDuringTradingHours() {
        MarketCalendar calendar = new MarketCalendar();

        // 10:00 AM ET on a Wednesday
        ZonedDateTime time = ZonedDateTime.of(
                LocalDate.of(2024, 1, 10),
                LocalTime.of(10, 0),
                ZoneId.of("America/New_York")
        );

        assertTrue(calendar.isMarketOpen(time.toInstant()));
    }

    @Test
    void testMarketClosedBeforeOpen() {
        MarketCalendar calendar = new MarketCalendar();

        // 8:00 AM ET (before 9:30 AM)
        ZonedDateTime time = ZonedDateTime.of(
                LocalDate.of(2024, 1, 10),
                LocalTime.of(8, 0),
                ZoneId.of("America/New_York")
        );

        assertFalse(calendar.isMarketOpen(time.toInstant()));
    }

    @Test
    void testMarketClosedAfterClose() {
        MarketCalendar calendar = new MarketCalendar();

        // 5:00 PM ET (after 4:00 PM)
        ZonedDateTime time = ZonedDateTime.of(
                LocalDate.of(2024, 1, 10),
                LocalTime.of(17, 0),
                ZoneId.of("America/New_York")
        );

        assertFalse(calendar.isMarketOpen(time.toInstant()));
    }

    @Test
    void testMarketClosedOnWeekend() {
        MarketCalendar calendar = new MarketCalendar();

        // Saturday
        ZonedDateTime saturday = ZonedDateTime.of(
                LocalDate.of(2024, 1, 13),
                LocalTime.of(10, 0),
                ZoneId.of("America/New_York")
        );

        // Sunday
        ZonedDateTime sunday = ZonedDateTime.of(
                LocalDate.of(2024, 1, 14),
                LocalTime.of(10, 0),
                ZoneId.of("America/New_York")
        );

        assertFalse(calendar.isMarketOpen(saturday.toInstant()));
        assertFalse(calendar.isMarketOpen(sunday.toInstant()));
    }

    @Test
    void testMarketClosedOnHoliday() {
        int currentYear = LocalDate.now().getYear();
        Set<LocalDate> customHolidays = Set.of(LocalDate.of(currentYear, 1, 1));
        MarketCalendar calendar = new MarketCalendar(customHolidays);

        // New Year's Day (current year)
        ZonedDateTime newYears = ZonedDateTime.of(
                LocalDate.of(currentYear, 1, 1),
                LocalTime.of(10, 0),
                ZoneId.of("America/New_York")
        );

        assertFalse(calendar.isMarketOpen(newYears.toInstant()));
    }

    @Test
    void testMarketClosedAtBoundary() {
        MarketCalendar calendar = new MarketCalendar();

        // Exactly at open (9:30 AM)
        ZonedDateTime atOpen = ZonedDateTime.of(
                LocalDate.of(2024, 1, 10),
                LocalTime.of(9, 30),
                ZoneId.of("America/New_York")
        );

        // Exactly at close (4:00 PM)
        ZonedDateTime atClose = ZonedDateTime.of(
                LocalDate.of(2024, 1, 10),
                LocalTime.of(16, 0),
                ZoneId.of("America/New_York")
        );

        assertTrue(calendar.isMarketOpen(atOpen.toInstant()));
        assertFalse(calendar.isMarketOpen(atClose.toInstant()));
    }

    @Test
    void testNextMarketOpen() {
        MarketCalendar calendar = new MarketCalendar();

        // Sunday evening
        ZonedDateTime sunday = ZonedDateTime.of(
                LocalDate.of(2024, 1, 14),
                LocalTime.of(20, 0),
                ZoneId.of("America/New_York")
        );

        Instant nextOpen = calendar.nextMarketOpen(sunday.toInstant());
        ZonedDateTime expected = ZonedDateTime.of(
                LocalDate.of(2024, 1, 15),
                LocalTime.of(9, 30),
                ZoneId.of("America/New_York")
        );

        assertEquals(expected.toInstant(), nextOpen);
    }

    @Test
    void testNextMarketClose() {
        MarketCalendar calendar = new MarketCalendar();

        // Monday morning
        ZonedDateTime monday = ZonedDateTime.of(
                LocalDate.of(2024, 1, 8),
                LocalTime.of(10, 0),
                ZoneId.of("America/New_York")
        );

        Instant nextClose = calendar.nextMarketClose(monday.toInstant());
        ZonedDateTime expected = ZonedDateTime.of(
                LocalDate.of(2024, 1, 8),
                LocalTime.of(16, 0),
                ZoneId.of("America/New_York")
        );

        assertEquals(expected.toInstant(), nextClose);
    }

    @Test
    void testTimeUntilClose() {
        MarketCalendar calendar = new MarketCalendar();

        // 2:00 PM ET
        ZonedDateTime time = ZonedDateTime.of(
                LocalDate.of(2024, 1, 10),
                LocalTime.of(14, 0),
                ZoneId.of("America/New_York")
        );

        java.time.Duration duration = calendar.timeUntilClose(time.toInstant());
        assertEquals(2, duration.toHours());
    }

    @Test
    void testTimeUntilOpen() {
        MarketCalendar calendar = new MarketCalendar();

        // Sunday evening
        ZonedDateTime time = ZonedDateTime.of(
                LocalDate.of(2024, 1, 14),
                LocalTime.of(20, 0),
                ZoneId.of("America/New_York")
        );

        java.time.Duration duration = calendar.timeUntilOpen(time.toInstant());
        assertTrue(duration.toHours() > 10); // Should be more than 10 hours
    }

    @Test
    void testGetStatus() {
        MarketCalendar calendar = new MarketCalendar();

        // During trading hours
        ZonedDateTime openTime = ZonedDateTime.of(
                LocalDate.of(2024, 1, 10),
                LocalTime.of(10, 0),
                ZoneId.of("America/New_York")
        );

        String status = calendar.getStatus(openTime.toInstant());
        assertTrue(status.contains("OPEN"));
        assertTrue(status.contains("closes in"));

        // Outside trading hours
        ZonedDateTime closedTime = ZonedDateTime.of(
                LocalDate.of(2024, 1, 14),
                LocalTime.of(20, 0),
                ZoneId.of("America/New_York")
        );

        status = calendar.getStatus(closedTime.toInstant());
        assertTrue(status.contains("CLOSED"));
        assertTrue(status.contains("opens in"));
    }

    @Test
    void testCustomHolidays() {
        Set<LocalDate> customHolidays = Set.of(
                LocalDate.of(2024, 7, 4),
                LocalDate.of(2024, 12, 25)
        );

        MarketCalendar calendar = new MarketCalendar(customHolidays);

        ZonedDateTime independenceDay = ZonedDateTime.of(
                LocalDate.of(2024, 7, 4),
                LocalTime.of(10, 0),
                ZoneId.of("America/New_York")
        );

        assertFalse(calendar.isMarketOpen(independenceDay.toInstant()));
    }

    @Test
    void testIsHoliday() {
        int currentYear = LocalDate.now().getYear();
        Set<LocalDate> customHolidays = Set.of(LocalDate.of(currentYear, 1, 1));
        MarketCalendar calendar = new MarketCalendar(customHolidays);

        assertTrue(calendar.isHoliday(LocalDate.of(currentYear, 1, 1))); // New Year's Day
        assertFalse(calendar.isHoliday(LocalDate.of(currentYear, 1, 2))); // Regular day
    }

    @Test
    void testGetHolidays() {
        int currentYear = LocalDate.now().getYear();
        Set<LocalDate> customHolidays = Set.of(LocalDate.of(currentYear, 1, 1));
        MarketCalendar calendar = new MarketCalendar(customHolidays);
        Set<LocalDate> holidays = calendar.getHolidays();

        assertNotNull(holidays);
        assertFalse(holidays.isEmpty());
        assertTrue(holidays.contains(LocalDate.of(currentYear, 1, 1)));
    }

    @Test
    void testTimeZoneHandling() {
        MarketCalendar calendar = new MarketCalendar();

        // Same instant, different time zones
        Instant instant = ZonedDateTime.of(
                LocalDate.of(2024, 1, 10),
                LocalTime.of(15, 30), // 3:30 PM ET
                ZoneId.of("America/New_York")
        ).toInstant();

        // Should be open in ET (3:30 PM is before 4:00 PM)
        assertTrue(calendar.isMarketOpen(instant));
    }
}
