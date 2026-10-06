package com.sbk.optionspricer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

class VolatilitySurfaceCalibratorTest {

    private static final LocalDate AS_OF = LocalDate.of(2025, 3, 3);
    private static final double SPOT = 100.0;
    private static final double RATE = 0.05;
    private static final double DIVIDEND = 0.01;

    private static double yearsTo(LocalDate expiry) {
        return ChronoUnit.DAYS.between(AS_OF, expiry) / 365.0;
    }

    private static OptionQuote quoteAt(LocalDate expiry, double strike, OptionType type, double vol) {
        double mid = BlackScholesPricer.price(type, SPOT, strike, yearsTo(expiry), RATE, vol, DIVIDEND);
        return new OptionQuote("SPY", expiry, strike, type, mid - 0.0005, mid + 0.0005, 0.0, 100, 1000);
    }

    @Test
    @DisplayName("Recovers the volatility that generated a quote")
    void recoversTheGeneratingVolatility() {
        LocalDate expiry = AS_OF.plusDays(30);
        OptionQuote quote = quoteAt(expiry, 105.0, OptionType.CALL, 0.25);

        OptionalDouble iv = VolatilitySurfaceCalibrator.impliedVolatility(quote, SPOT, RATE, DIVIDEND, AS_OF);

        assertTrue(iv.isPresent());
        assertEquals(0.25, iv.getAsDouble(), 1e-7);
    }

    @Test
    @DisplayName("The valuation date is explicit: the same quote implies a different vol on a different day")
    void resultDependsOnTheValuationDateNotOnTheClock() {
        LocalDate expiry = AS_OF.plusDays(30);
        OptionQuote quote = quoteAt(expiry, 100.0, OptionType.CALL, 0.30);

        double onDay = VolatilitySurfaceCalibrator.impliedVolatility(quote, SPOT, RATE, DIVIDEND, AS_OF).getAsDouble();
        double tenDaysLater = VolatilitySurfaceCalibrator.impliedVolatility(quote, SPOT, RATE, DIVIDEND, AS_OF.plusDays(10)).getAsDouble();

        assertEquals(0.30, onDay, 1e-7);
        assertTrue(tenDaysLater > onDay + 0.05, "less time left for the same price means a higher vol: " + tenDaysLater);
    }

    @Test
    @DisplayName("No usable price means no implied vol, not an invented 20%")
    void unusablePricesYieldNothing() {
        for (double price : new double[]{-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertTrue(VolatilitySurfaceCalibrator.impliedVolatility(SPOT, 100.0, 0.5, RATE, DIVIDEND, OptionType.CALL, price).isEmpty(), "price " + price);
        }
    }

    @Test
    @DisplayName("A price no volatility can produce yields nothing, not a heuristic smile")
    void impossiblePricesYieldNothing() {
        // A call can never be worth more than the spot.
        assertTrue(VolatilitySurfaceCalibrator.impliedVolatility(SPOT, 100.0, 0.5, RATE, DIVIDEND, OptionType.CALL, 150.0).isEmpty());
        // Below intrinsic value.
        assertTrue(VolatilitySurfaceCalibrator.impliedVolatility(SPOT, 80.0, 0.5, RATE, DIVIDEND, OptionType.CALL, 5.0).isEmpty());
    }

    @Test
    @DisplayName("An option that has expired or expires today has no implied vol")
    void noTimeLeftMeansNoImpliedVol() {
        OptionQuote expiresToday = new OptionQuote("SPY", AS_OF, 100.0, OptionType.CALL, 1.0, 1.2, 0.0, 1, 1);
        OptionQuote expired = new OptionQuote("SPY", AS_OF.minusDays(3), 100.0, OptionType.CALL, 1.0, 1.2, 0.0, 1, 1);

        assertTrue(VolatilitySurfaceCalibrator.impliedVolatility(expiresToday, SPOT, RATE, DIVIDEND, AS_OF).isEmpty());
        assertTrue(VolatilitySurfaceCalibrator.impliedVolatility(expired, SPOT, RATE, DIVIDEND, AS_OF).isEmpty());
    }

    @Test
    @DisplayName("Chain: sorted by strike, only the requested expiry, unsolvable quotes omitted, vols are real")
    void calibratesAChainHonestly() {
        LocalDate expiry = AS_OF.plusDays(60);
        LocalDate otherExpiry = AS_OF.plusDays(120);
        List<OptionQuote> raw = List.of(
                quoteAt(expiry, 105.0, OptionType.CALL, 0.22),
                quoteAt(expiry, 95.0, OptionType.CALL, 0.28),
                quoteAt(expiry, 100.0, OptionType.CALL, 0.25),
                quoteAt(otherExpiry, 100.0, OptionType.CALL, 0.40),                                   // other expiry: ignored
                new OptionQuote("SPY", expiry, 110.0, OptionType.CALL, 0.0, 0.0, 0.0, 0, 0),         // no price: omitted
                new OptionQuote("SPY", expiry, 80.0, OptionType.CALL, 1.0, 1.1, 0.0, 5, 5));         // below intrinsic: omitted

        List<OptionQuote> calibrated = VolatilitySurfaceCalibrator.calibrateChain("SPY", expiry, SPOT, raw, RATE, DIVIDEND, AS_OF);

        assertEquals(3, calibrated.size());
        assertEquals(List.of(95.0, 100.0, 105.0), calibrated.stream().map(OptionQuote::strike).toList());
        assertEquals(List.of(0.28, 0.25, 0.22), calibrated.stream().map(q -> Math.round(q.impliedVolatility() * 1e6) / 1e6).toList());
        assertTrue(calibrated.stream().allMatch(q -> q.expiry().equals(expiry) && q.symbol().equals("SPY")));
    }

    @Test
    @DisplayName("Input validation")
    void validation() {
        assertThrows(IllegalArgumentException.class, () ->
                VolatilitySurfaceCalibrator.impliedVolatility(null, SPOT, RATE, DIVIDEND, AS_OF));
        assertThrows(IllegalArgumentException.class, () ->
                VolatilitySurfaceCalibrator.impliedVolatility(quoteAt(AS_OF.plusDays(30), 100.0, OptionType.CALL, 0.2), SPOT, RATE, DIVIDEND, null));
        assertThrows(IllegalArgumentException.class, () ->
                VolatilitySurfaceCalibrator.calibrateChain("", AS_OF.plusDays(10), SPOT, List.of(), RATE, 0.0, AS_OF));
        assertThrows(IllegalArgumentException.class, () ->
                VolatilitySurfaceCalibrator.calibrateChain("SPY", null, SPOT, List.of(), RATE, 0.0, AS_OF));
        assertThrows(IllegalArgumentException.class, () ->
                VolatilitySurfaceCalibrator.calibrateChain("SPY", AS_OF.plusDays(10), -50.0, List.of(), RATE, 0.0, AS_OF));
        assertThrows(IllegalArgumentException.class, () ->
                VolatilitySurfaceCalibrator.impliedVolatility(Double.NaN, 100.0, 0.5, RATE, 0.0, OptionType.CALL, 5.0));
    }
}
