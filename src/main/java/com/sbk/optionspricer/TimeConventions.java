package com.sbk.optionspricer;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The single day-count convention used to turn dates into option times: ACT/365 Fixed (actual calendar
 * days over a 365-day year). Before this existed, different classes divided by 365, 365.25 and a
 * hard-coded 0.1, so the same expiry meant different times in different places.
 *
 * <p>This is a calendar-day convention with no business-day or holiday logic; a leap year is 366/365.
 */
public final class TimeConventions {

    public static final double DAYS_PER_YEAR = 365.0;

    private TimeConventions() {
    }

    /** ACT/365F year fraction from {@code from} to {@code to}; negative if {@code to} is before {@code from}. */
    public static double yearFraction(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("dates must not be null");
        }
        return ChronoUnit.DAYS.between(from, to) / DAYS_PER_YEAR;
    }

    /** ACT/365F year fraction with sub-second precision between two instants. */
    public static double yearFraction(java.time.Instant from, java.time.Instant to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("instants must not be null");
        }
        return java.time.Duration.between(from, to).toMillis() / (DAYS_PER_YEAR * 86_400_000.0);
    }
}
