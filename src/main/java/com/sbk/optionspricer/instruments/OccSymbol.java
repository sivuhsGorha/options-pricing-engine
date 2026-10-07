package com.sbk.optionspricer.instruments;

import com.sbk.optionspricer.OptionType;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OCC option symbology, the contract identifier the whole system books positions under:
 * root (1 to 6 letters) + expiry yyMMdd + C or P + strike in thousandths, 8 digits. {@code SPY261120C00780000}
 * is the SPY 20 Nov 2026 780 call. Cboe publishes contracts in this form and the position tracker keys on it.
 */
public final class OccSymbol {

    public record Parsed(String underlying, LocalDate expiry, OptionType type, double strike) {}

    private static final Pattern PATTERN = Pattern.compile("^([A-Z]{1,6})(\\d{2})(\\d{2})(\\d{2})([CP])(\\d{8})$");
    private static final Pattern ROOT = Pattern.compile("^[A-Z]{1,6}$");

    private OccSymbol() {
    }

    public static String format(String underlying, LocalDate expiry, OptionType type, double strike) {
        if (underlying == null || expiry == null || type == null) {
            throw new IllegalArgumentException("underlying, expiry and type must not be null");
        }
        String root = underlying.trim().toUpperCase(Locale.ROOT);
        if (!ROOT.matcher(root).matches()) {
            throw new IllegalArgumentException("OCC root must be 1 to 6 letters: " + underlying);
        }
        if (!Double.isFinite(strike) || strike <= 0.0) {
            throw new IllegalArgumentException("strike must be positive and finite: " + strike);
        }
        long thousandths = Math.round(strike * 1000.0);
        if (thousandths > 99_999_999L) {
            throw new IllegalArgumentException("strike too large for OCC symbology: " + strike);
        }
        return String.format(Locale.ROOT, "%s%02d%02d%02d%s%08d", root, expiry.getYear() % 100, expiry.getMonthValue(),
                expiry.getDayOfMonth(), type == OptionType.CALL ? "C" : "P", thousandths);
    }

    /** Empty for anything that is not a well-formed OCC symbol (a plain ticker such as {@code SPY} included). */
    public static Optional<Parsed> parse(String symbol) {
        if (symbol == null) {
            return Optional.empty();
        }
        Matcher m = PATTERN.matcher(symbol.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            LocalDate expiry = LocalDate.of(2000 + Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
            double strike = Long.parseLong(m.group(6)) / 1000.0;
            if (strike <= 0.0) {
                return Optional.empty();
            }
            return Optional.of(new Parsed(m.group(1), expiry, "C".equals(m.group(5)) ? OptionType.CALL : OptionType.PUT, strike));
        } catch (RuntimeException invalidDate) {
            return Optional.empty();
        }
    }
}
