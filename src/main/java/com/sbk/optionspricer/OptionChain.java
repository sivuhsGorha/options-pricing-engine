package com.sbk.optionspricer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A snapshot of an option chain for a single symbol and expiry.
 */
public record OptionChain(
        String symbol,
        LocalDate expiry,
        double spot,
        List<OptionQuote> quotes
) {
    public OptionChain {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (expiry == null) {
            throw new IllegalArgumentException("expiry must not be null");
        }
        if (!Double.isFinite(spot) || spot <= 0.0) {
            throw new IllegalArgumentException("spot must be finite and positive");
        }
        quotes = quotes == null ? List.of() : List.copyOf(quotes);
    }

    public List<OptionQuote> calls() {
        return quotes.stream()
                .filter(quote -> quote.type() == OptionType.CALL)
                .sorted((left, right) -> Double.compare(left.strike(), right.strike()))
                .toList();
    }

    public List<OptionQuote> puts() {
        return quotes.stream()
                .filter(quote -> quote.type() == OptionType.PUT)
                .sorted((left, right) -> Double.compare(left.strike(), right.strike()))
                .toList();
    }

    public OptionChain withQuotes(List<OptionQuote> newQuotes) {
        return new OptionChain(symbol, expiry, spot, new ArrayList<>(newQuotes));
    }

    public List<OptionQuote> sortedQuotes() {
        List<OptionQuote> copy = new ArrayList<>(quotes);
        copy.sort((left, right) -> {
            int byType = left.type().compareTo(right.type());
            if (byType != 0) return byType;
            return Double.compare(left.strike(), right.strike());
        });
        return Collections.unmodifiableList(copy);
    }
}
