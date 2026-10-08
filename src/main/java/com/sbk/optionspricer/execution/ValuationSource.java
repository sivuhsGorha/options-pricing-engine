package com.sbk.optionspricer.execution;

import java.util.Optional;

/** Where the dashboard and the risk engine get the latest mark-to-market of the book. */
public interface ValuationSource {
    Optional<Valuation> latestValuation();

    /** Why there is no valuation yet, or the last problem; empty when all is well. */
    String valuationStatus();
}
