package com.sbk.optionspricer.market;

import com.sbk.optionspricer.OptionChain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** The option chains the system currently holds, and a tradable snapshot for any contract in them. */
public interface OptionMarketData {

    /** Expiries of the loaded chains, ascending; empty before the first load. */
    List<LocalDate> loadedExpiries();

    Optional<OptionChain> chain(LocalDate expiry);

    /** Snapshot for an OCC contract symbol, or empty when the contract is not in a loaded chain or has no price. */
    Optional<MarketSnapshot> optionQuote(String contractSymbol);
}
