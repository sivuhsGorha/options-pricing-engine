package com.sbk.optionspricer;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

public interface OptionChainProvider {

    /**
     * A chain together with the provider that produced it, so a synthetic fallback can never pass as market
     * data, and the reasons any earlier provider failed, so a fallback can say why it happened.
     */
    record SourcedChain(OptionChain chain, String source, boolean marketData, List<String> notes, java.time.Instant asOf) {
        public SourcedChain {
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        public SourcedChain(OptionChain chain, String source, boolean marketData, List<String> notes) {
            this(chain, source, marketData, notes, null);
        }

        public SourcedChain(OptionChain chain, String source, boolean marketData) {
            this(chain, source, marketData, List.of(), null);
        }
    }

    OptionChain getOptionChain(String symbol, LocalDate expiry);

    default OptionChain getOptionChain(String symbol) {
        return getOptionChain(symbol, LocalDate.now());
    }

    /** Short upper-case name shown as the surface's provenance. */
    default String sourceName() {
        return getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT);
    }

    /** False for providers that generate prices rather than fetch them. */
    default boolean isMarketData() {
        return true;
    }

    default SourcedChain getSourcedChain(String symbol, LocalDate expiry) {
        return new SourcedChain(getOptionChain(symbol, expiry), sourceName(), isMarketData());
    }

    /**
     * Expiries this provider can serve for the symbol, ascending. The default is the next eight monthly (third
     * Friday) expiries, enough for a selector to find distinct dates near 1, 2, 3 and 6 months; every provider
     * that generates chains accepts them. A provider with a real listing overrides this so that only listed
     * dates are requested.
     */
    default List<LocalDate> listExpiries(String symbol, LocalDate asOf) {
        return thirdFridays(asOf, 8);
    }

    /** The next {@code count} third-Friday expiries at least a week after {@code from}, ascending. */
    static List<LocalDate> thirdFridays(LocalDate from, int count) {
        List<LocalDate> out = new ArrayList<>(count);
        LocalDate month = from.withDayOfMonth(1);
        while (out.size() < count) {
            LocalDate thirdFriday = month.with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.FRIDAY));
            if (!thirdFriday.isBefore(from.plusDays(7))) {
                out.add(thirdFriday);
            }
            month = month.plusMonths(1);
        }
        return List.copyOf(out);
    }
}
