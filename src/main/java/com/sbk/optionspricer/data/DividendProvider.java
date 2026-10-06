package com.sbk.optionspricer.data;

import com.sbk.optionspricer.models.pde.DiscreteDividendPricer.DiscreteDividend;

import java.time.LocalDate;
import java.util.List;

/**
 * Interface for fetching dividend schedules for a given stock symbol.
 */
public interface DividendProvider {
    /**
     * Fetches dividends with ex-dates falling between `from` and `to`.
     * The returned DiscreteDividend instances should have their `timeToDividend` 
     * calculated in years relative to `from`.
     */
    List<DiscreteDividend> getDividends(String symbol, LocalDate from, LocalDate to);
}
