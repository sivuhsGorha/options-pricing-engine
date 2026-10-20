package com.sbk.optionspricer.execution;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Found live on 2026-10-19 and 20: a day limit set exactly at the indicative ask (a buy) or bid (a sell) often did not fill,
 * because Alpaca's paper fills match against the real market, which differs from the indicative quote by a few cents; the
 * call that did fill had a limit above the touch and filled below it. {@code execution.limit_cushion_bps} widens the limit past
 * the touch by a few basis points (default 0: unchanged). A marketable limit still fills at the market price, so the cushion is
 * a ceiling on what is paid or a floor on what is received, not a price chased.
 */
class AlpacaLimitCushionTest {

    private static final String ID = "92983a34-4961-4125-84ce-3faddb66c601";

    private static final class SteppingClock extends Clock {
        Instant now = Instant.parse("2026-10-20T14:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static AlpacaPaperTransport transport(FakeAlpaca fake, double cushionBps) {
        SteppingClock clock = new SteppingClock();
        AlpacaPaperTransport t = new AlpacaPaperTransport(new AlpacaPaperClient(fake), Duration.ofSeconds(2), Duration.ofMillis(500), clock,
                d -> clock.now = clock.now.plus(d));
        t.setLimitCushionBps(cushionBps);
        return t;
    }

    @Test
    void withNoCushionTheLimitIsExactlyTheTouchAsBefore() {
        assertEquals(777.30, AlpacaPaperTransport.limitPrice(new Order(0, true, 10, 777.20), 777.10, 777.30, 0.0), 1e-9);
        assertEquals(9.85, AlpacaPaperTransport.limitPrice(new Order(0, false, 3, 9.9), 9.85, 9.95, 0.0), 1e-9);
    }

    @Test
    void aCushionRaisesABuyLimitAndLowersASellLimitAndRoundsInTheSafeDirection() {
        // 777.30 x 1.003 = 779.6319 -> up to the next cent; 9.85 x 0.997 = 9.82045 -> down to the cent
        assertEquals(779.64, AlpacaPaperTransport.limitPrice(new Order(0, true, 10, 777.20), 777.10, 777.30, 30.0), 1e-9);
        assertEquals(9.82, AlpacaPaperTransport.limitPrice(new Order(0, false, 3, 9.9), 9.85, 9.95, 30.0), 1e-9);
        // never a limit that is worse for the venue than the touch itself
        assertTrue(AlpacaPaperTransport.limitPrice(new Order(0, true, 1, 1.0), 0.99, 1.00, 1.0) >= 1.00);
        assertTrue(AlpacaPaperTransport.limitPrice(new Order(0, false, 1, 1.0), 1.00, 1.01, 1.0) <= 1.00);
    }

    @Test
    void theCushionGoesOnTheOrderSentToAlpacaAndIsSaidInTheNoFillMessage() {
        FakeAlpaca fake = new FakeAlpaca().post(200, FakeAlpaca.orderJson(ID, "filled", 10, 10, 777.31, "SPY", "buy"));

        ExecutionResult r = transport(fake, 30.0).transmit(new Order(0, true, 10, 777.20), "spy", 777.10, 777.30);

        assertTrue(r.executed(), r.message());
        assertTrue(fake.calls.get(0).contains("\"limit_price\":\"779.64\""), fake.calls.get(0));
    }

    @Test
    void aMultiLegBuyIsCushionedOnItsNetDebit() {
        FakeAlpaca fake = new FakeAlpaca().post(200, FakeAlpaca.orderJson(ID, "filled", 1, 1, 25.10, "", "buy"));

        ExecutionResult r = transport(fake, 30.0).transmitMultiLeg(
                List.of(new Order(0, true, 1, 12.6), new Order(0, true, 1, 12.6)),
                List.of("SPY261120C00769000", "SPY261120P00769000"), List.of(12.5, 12.5), List.of(12.6, 12.6), 25.205);

        assertTrue(r.executed(), r.message());
        // 25.205 x 1.003 = 25.2806 -> 25.29
        assertTrue(fake.calls.get(0).contains("\"limit_price\":\"25.29\""), fake.calls.get(0));
    }

    @Test
    void aMultiLegWithNoCushionKeepsItsPriceAsBefore() {
        FakeAlpaca fake = new FakeAlpaca().post(200, FakeAlpaca.orderJson(ID, "filled", 1, 1, 8.10, "", "buy"));

        transport(fake, 0.0).transmitMultiLeg(
                List.of(new Order(0, true, 1, 4.4), new Order(0, true, 1, 3.7)),
                List.of("SPY261120C00780000", "SPY261120P00780000"), List.of(4.3, 3.6), List.of(4.5, 3.8), 8.10);

        assertTrue(fake.calls.get(0).contains("\"limit_price\":\"8.10\""), fake.calls.get(0));
    }

    @Test
    void aCushionThatIsNegativeNotANumberOrAbsurdlyLargeIsRefused() {
        AlpacaPaperTransport t = transport(new FakeAlpaca(), 0.0);
        assertThrows(IllegalArgumentException.class, () -> t.setLimitCushionBps(-1.0));
        assertThrows(IllegalArgumentException.class, () -> t.setLimitCushionBps(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> t.setLimitCushionBps(501.0));
        t.setLimitCushionBps(500.0);
        assertEquals(500.0, t.limitCushionBps(), 0.0);
    }
}
