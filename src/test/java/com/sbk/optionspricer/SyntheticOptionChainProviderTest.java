package com.sbk.optionspricer;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class SyntheticOptionChainProviderTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 3, 3);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-03-03T12:00:00Z"), ZoneOffset.UTC);

    private static OptionChain chain(double vol, LocalDate expiry) {
        return new SyntheticOptionChainProvider(100.0, vol, 0.05, 0.01, CLOCK).getOptionChain("SPY", expiry);
    }

    private static OptionQuote find(OptionChain chain, double strike, OptionType type) {
        return chain.quotes().stream().filter(q -> q.strike() == strike && q.type() == type).findFirst().orElseThrow();
    }

    @Test
    void pricesFollowTheRequestedExpiryNotAHardCodedTime() {
        for (int days : new int[]{7, 30, 90, 365}) {
            double expected = BlackScholesPricer.price(OptionType.CALL, 100.0, 100.0, days / 365.0, 0.05, 0.2, 0.01);
            OptionQuote call = find(chain(0.2, TODAY.plusDays(days)), 100.0, OptionType.CALL);
            assertEquals(expected, call.midPrice(), 1e-9, days + " days");
        }
    }

    @Test
    void impliedVolatilityIsTheVolatilityTheChainWasBuiltWith() {
        // The prices are generated from this volatility, so no solver (and no made-up fallback) is involved.
        OptionChain chain = chain(0.31, TODAY.plusDays(45));
        assertFalse(chain.quotes().isEmpty());
        for (OptionQuote quote : chain.quotes()) {
            assertEquals(0.31, quote.impliedVolatility(), 0.0, quote.type() + " " + quote.strike());
        }
    }

    @Test
    void anExpiryThatIsNotInTheFutureIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> chain(0.2, TODAY));
        assertThrows(IllegalArgumentException.class, () -> chain(0.2, TODAY.minusDays(5)));
    }

    @Test
    void quotesBracketTheModelPrice() {
        OptionChain chain = chain(0.25, TODAY.plusDays(60));
        for (OptionQuote quote : chain.quotes()) {
            assertTrue(quote.bid() <= quote.ask(), quote.toString());
            assertTrue(quote.bid() >= 0.0);
        }
    }
}
