package com.sbk.optionspricer.data;

import com.sbk.optionspricer.models.pde.DiscreteDividendPricer.DiscreteDividend;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Projects future dividends based on historical patterns fetched from Yahoo Finance.
 *
 * <p>This is a naive model: each past dividend is assumed to recur on the same date one year later
 * with the same amount, so special dividends are projected as if they were regular. A production
 * model would use a corporate-actions feed.
 */
public class DividendForecaster implements DividendProvider {

    /** History older than this is not a reliable guide to the next payout. */
    private static final int MAX_HISTORY_YEARS = 5;

    private final YahooDividendProvider historyProvider;

    public DividendForecaster() {
        this(new YahooDividendProvider());
    }

    DividendForecaster(YahooDividendProvider historyProvider) {
        this.historyProvider = historyProvider;
    }

    @Override
    public List<DiscreteDividend> getDividends(String symbol, LocalDate from, LocalDate to) {
        List<YahooDividendProvider.HistoricalDividend> history = historyProvider.getHistoricalDividends(symbol);
        List<DiscreteDividend> projected = new ArrayList<>();

        for (YahooDividendProvider.HistoricalDividend pastDiv : history) {
            if (pastDiv.exDate().isBefore(from.minusYears(MAX_HISTORY_YEARS))) {
                continue;
            }
            // Roll forward by whole years until the date is on or after `from` (bounded by the age filter above).
            LocalDate nextExDate = pastDiv.exDate().plusYears(1);
            while (nextExDate.isBefore(from)) {
                nextExDate = nextExDate.plusYears(1);
            }
            if (!nextExDate.isAfter(to)) {
                double timeToDividendInYears = com.sbk.optionspricer.TimeConventions.yearFraction(from, nextExDate);
                if (timeToDividendInYears > 0) {
                    projected.add(new DiscreteDividend(timeToDividendInYears, pastDiv.amount()));
                }
            }
        }

        return projected;
    }
}
