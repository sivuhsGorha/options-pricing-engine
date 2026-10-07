package com.sbk.optionspricer.execution;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Converts decimal prices to the scaled integer "ticks" that go on the wire (1 tick = 0.0001), and back.
 *
 * <p>The conversion works on the shortest decimal form of the double ({@link BigDecimal#valueOf(double)}), so a
 * price typed as {@code 1.6} becomes exactly 16,000 ticks. The previous {@code (long) (price * 10000)} truncated
 * the binary product and produced 15,999 for the same price, a one-tick error on the wire. Halves round to even.
 * Non-finite, negative and out-of-range prices are rejected rather than encoded.
 */
public final class PriceScale {

    /** Decimal places carried on the wire. */
    public static final int DECIMALS = 4;
    public static final double TICKS_PER_UNIT = 10_000.0;

    private PriceScale() {
    }

    public static long toTicks(double price) {
        if (!Double.isFinite(price) || price < 0.0) {
            throw new IllegalArgumentException("price must be finite and non-negative: " + price);
        }
        try {
            return BigDecimal.valueOf(price).setScale(DECIMALS, RoundingMode.HALF_EVEN).unscaledValue().longValueExact();
        } catch (ArithmeticException tooLarge) {
            throw new IllegalArgumentException("price is too large to encode: " + price);
        }
    }

    public static double fromTicks(long ticks) {
        return ticks / TICKS_PER_UNIT;
    }
}
