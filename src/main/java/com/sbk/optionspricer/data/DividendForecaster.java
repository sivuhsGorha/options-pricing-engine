package com.sbk.optionspricer.data;

import com.sbk.optionspricer.models.pde.DiscreteDividendPricer.DiscreteDividend;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Projects future dividends based on historical patterns fetched from Yahoo Finance.
 */
public class DividendForecaster implements DividendProvider {

    private final YahooDividendProvider historyProvider;

    public DividendForecaster() {
        this.historyProvider = new YahooDividendProvider();
    }

    @Override
    public List<DiscreteDividend> getDividends(String symbol, LocalDate from, LocalDate to) {
        List<YahooDividendProvider.HistoricalDividend> history = historyProvider.getHistoricalDividends(symbol);
        List<DiscreteDividend> projected = new ArrayList<>();

        if (history.isEmpty()) {
            return projected;
        }

        // Simple naive projection: Assume the dividend amount is the same as last year, 
        // paid on roughly the same date (+364 days to keep same day of week, or +365)
        // A full production model would use IEX Cloud or a corporate actions feed.

        for (YahooDividendProvider.HistoricalDividend pastDiv : history) {
            LocalDate nextExDate = pastDiv.exDate().plusYears(1);
            
            // Fast-forward to the future range if the history is older
            while (nextExDate.isBefore(from)) {
                nextExDate = nextExDate.plusYears(1);
            }

            if (!nextExDate.isAfter(to)) {
                double timeToDividendInYears = ChronoUnit.DAYS.between(from, nextExDate) / 365.25;
                if (timeToDividendInYears > 0) {
                    projected.add(new DiscreteDividend(timeToDividendInYears, pastDiv.amount()));
                }
            }
        }

        return projected;
    }
}
