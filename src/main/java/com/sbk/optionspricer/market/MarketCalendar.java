package com.sbk.optionspricer.market;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

/**
 * Market calendar for NYSE trading hours and holidays.
 *
 * Features:
 * - Trading hours: 9:30 AM - 4:00 PM ET, Monday-Friday
 * - Holiday calendar for NYSE holidays
 * - Methods to check if market is open/closed at a given time
 * - Stand-down logic for strategy during non-trading hours
 */
public final class MarketCalendar {

    private static final ZoneId NYSE_ZONE = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);
    private static final LocalTime EARLY_CLOSE = LocalTime.of(13, 0);

    private final Set<LocalDate> holidays;

    public MarketCalendar() {
        this.holidays = new HashSet<>();
        initializeHolidays();
    }

    /**
     * Creates a market calendar with custom holidays.
     *
     * @param holidays the set of holiday dates
     */
    public MarketCalendar(Set<LocalDate> holidays) {
        this.holidays = new HashSet<>(holidays);
    }

    /**
     * Initializes the holiday calendar with NYSE holidays.
     * These are the standard NYSE holidays. For production, this should be
     * updated annually or fetched from an API.
     */
    private void initializeHolidays() {
        // The engine runs for weeks: the year it started in is not enough across New Year, so the neighbours are loaded too.
        int thisYear = LocalDate.now().getYear();
        for (int year = thisYear - 1; year <= thisYear + 1; year++) {
            initializeHolidays(year);
        }
    }

    private void initializeHolidays(int year) {

        // New Year's Day
        addHolidayIfWeekday(LocalDate.of(year, 1, 1));

        // Martin Luther King Jr. Day (third Monday in January)
        addHoliday(monthNthWeekday(year, 1, DayOfWeek.MONDAY, 3));

        // Washington's Birthday (third Monday in February)
        addHoliday(monthNthWeekday(year, 2, DayOfWeek.MONDAY, 3));

        // Good Friday (Friday before Easter Sunday)
        addHoliday(goodFriday(year));

        // Memorial Day (last Monday in May)
        addHoliday(lastWeekdayOfMonth(year, 5, DayOfWeek.MONDAY));

        // Juneteenth (June 19, observed on Friday if Saturday, Monday if Sunday)
        addHoliday(observedHoliday(LocalDate.of(year, 6, 19)));

        // Independence Day (July 4, observed on Friday if Saturday, Monday if Sunday)
        addHoliday(observedHoliday(LocalDate.of(year, 7, 4)));

        // Labor Day (first Monday in September)
        addHoliday(monthNthWeekday(year, 9, DayOfWeek.MONDAY, 1));

        // Thanksgiving Day (fourth Thursday in November)
        addHoliday(monthNthWeekday(year, 11, DayOfWeek.THURSDAY, 4));

        // Christmas Day (December 25, observed on Friday if Saturday, Monday if Sunday)
        addHoliday(observedHoliday(LocalDate.of(year, 12, 25)));
    }

    /** The close on a date: 13:00 on the early-close days (the day after Thanksgiving, Christmas Eve, the day before Independence Day), else 16:00. */
    public LocalTime closeTime(LocalDate date) {
        if (holidays.contains(date) || date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return MARKET_CLOSE;
        }
        LocalDate thanksgiving = monthNthWeekday(date.getYear(), 11, DayOfWeek.THURSDAY, 4);
        boolean dayAfterThanksgiving = thanksgiving != null && date.equals(thanksgiving.plusDays(1));
        boolean christmasEve = date.getMonthValue() == 12 && date.getDayOfMonth() == 24;
        boolean julyThird = date.getMonthValue() == 7 && date.getDayOfMonth() == 3;
        return dayAfterThanksgiving || christmasEve || julyThird ? EARLY_CLOSE : MARKET_CLOSE;
    }

    private void addHolidayIfWeekday(LocalDate date) {
        if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
            holidays.add(date);
        }
    }

    private void addHoliday(LocalDate date) {
        if (date != null) {
            holidays.add(date);
        }
    }

    /**
     * Checks if the market is open at the given instant.
     *
     * @param instant the instant to check
     * @return true if the market is open
     */
    public boolean isMarketOpen(java.time.Instant instant) {
        ZonedDateTime nyTime = instant.atZone(NYSE_ZONE);
        LocalDate date = nyTime.toLocalDate();
        LocalTime time = nyTime.toLocalTime();

        // Check if it's a holiday
        if (holidays.contains(date)) {
            return false;
        }

        // Check if it's a weekend
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }

        // Check if it's within trading hours
        return !time.isBefore(MARKET_OPEN) && time.isBefore(closeTime(date));
    }

    /**
     * Checks if the market is closed at the given instant.
     *
     * @param instant the instant to check
     * @return true if the market is closed
     */
    public boolean isMarketClosed(java.time.Instant instant) {
        return !isMarketOpen(instant);
    }

    /**
     * Gets the next market open time after the given instant.
     *
     * @param instant the instant to check from
     * @return the next market open time
     */
    public java.time.Instant nextMarketOpen(java.time.Instant instant) {
        ZonedDateTime nyTime = instant.atZone(NYSE_ZONE);
        LocalDate date = nyTime.toLocalDate();
        LocalTime time = nyTime.toLocalTime();

        // Today still counts when the bell has not rung yet (and the day is a trading day); otherwise start from tomorrow
        if (!(isTradingDay(date) && time.isBefore(MARKET_OPEN))) {
            date = date.plusDays(1);
        }

        // Find the next weekday that's not a holiday
        while (!isTradingDay(date)) {
            date = date.plusDays(1);
        }

        return ZonedDateTime.of(date, MARKET_OPEN, NYSE_ZONE).toInstant();
    }

    /**
     * Gets the next market close time after the given instant.
     *
     * @param instant the instant to check from
     * @return the next market close time
     */
    public java.time.Instant nextMarketClose(java.time.Instant instant) {
        ZonedDateTime nyTime = instant.atZone(NYSE_ZONE);
        LocalDate date = nyTime.toLocalDate();
        LocalTime time = nyTime.toLocalTime();

        // Today's close counts while it has not passed (open or before the bell on a trading day); otherwise start from tomorrow
        if (!(isTradingDay(date) && time.isBefore(closeTime(date)))) {
            date = date.plusDays(1);
        }

        while (!isTradingDay(date)) {
            date = date.plusDays(1);
        }

        return ZonedDateTime.of(date, closeTime(date), NYSE_ZONE).toInstant();
    }

    /**
     * Gets the time until the next market close.
     *
     * @param instant the instant to check from
     * @return the duration until market close (may be negative if market is closed)
     */
    public java.time.Duration timeUntilClose(java.time.Instant instant) {
        java.time.Instant close = nextMarketClose(instant);
        return java.time.Duration.between(instant, close);
    }

    /**
     * Gets the time until the next market open.
     *
     * @param instant the instant to check from
     * @return the duration until market open
     */
    public java.time.Duration timeUntilOpen(java.time.Instant instant) {
        java.time.Instant open = nextMarketOpen(instant);
        return java.time.Duration.between(instant, open);
    }

    /**
     * Returns a human-readable status of the market.
     *
     * @param instant the instant to check
     * @return a status message
     */
    public String getStatus(java.time.Instant instant) {
        if (isMarketOpen(instant)) {
            java.time.Duration untilClose = timeUntilClose(instant);
            return String.format("OPEN (closes in %d minutes)", untilClose.toMinutes());
        } else {
            java.time.Duration untilOpen = timeUntilOpen(instant);
            return String.format("CLOSED (opens in %d minutes)", untilOpen.toMinutes());
        }
    }

    // Holiday calculation helpers

    private static LocalDate monthNthWeekday(int year, int month, DayOfWeek dayOfWeek, int n) {
        LocalDate date = LocalDate.of(year, month, 1);
        int count = 0;
        while (date.getMonthValue() == month) {
            if (date.getDayOfWeek() == dayOfWeek) {
                count++;
                if (count == n) {
                    return date;
                }
            }
            date = date.plusDays(1);
        }
        return null;
    }

    private static LocalDate lastWeekdayOfMonth(int year, int month, DayOfWeek dayOfWeek) {
        LocalDate date = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
        while (date.getDayOfWeek() != dayOfWeek) {
            date = date.minusDays(1);
        }
        return date;
    }

    private static LocalDate goodFriday(int year) {
        // Good Friday is the Friday before Easter Sunday
        // Using the algorithm for Easter (Anonymous Gregorian algorithm)
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        LocalDate easter = LocalDate.of(year, month, day);
        return easter.minusDays(2);
    }

    private static LocalDate observedHoliday(LocalDate date) {
        if (date == null) {
            return null;
        }
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY) {
            return date.minusDays(1); // Observe on Friday
        } else if (dayOfWeek == DayOfWeek.SUNDAY) {
            return date.plusDays(1); // Observe on Monday
        }
        return date;
    }

    /**
     * Gets the set of holidays for the current year.
     *
     * @return the set of holiday dates
     */
    public Set<LocalDate> getHolidays() {
        return new HashSet<>(holidays);
    }

    /**
     * Checks if a specific date is a holiday.
     *
     * @param date the date to check
     * @return true if it's a holiday
     */
    public boolean isTradingDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(date);
    }

    public boolean isHoliday(LocalDate date) {
        return holidays.contains(date);
    }
}
