package com.sbk.optionspricer;

import java.time.LocalDate;

/**
 * Strategy interface for fetching option chain snapshots from different data vendors.
 */
public interface OptionChainProvider {
    OptionChain getOptionChain(String symbol, LocalDate expiry);

    default OptionChain getOptionChain(String symbol) {
        return getOptionChain(symbol, LocalDate.now());
    }
}
