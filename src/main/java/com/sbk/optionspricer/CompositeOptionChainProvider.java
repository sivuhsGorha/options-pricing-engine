package com.sbk.optionspricer;

import java.time.LocalDate;
import java.util.List;

/**
 * Iterates through a list of OptionChainProviders, returning the result from the first one that succeeds.
 */
public class CompositeOptionChainProvider implements OptionChainProvider {
    
    private final List<OptionChainProvider> providers;

    public CompositeOptionChainProvider(List<OptionChainProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("Must provide at least one OptionChainProvider");
        }
        this.providers = List.copyOf(providers);
    }

    @Override
    public OptionChain getOptionChain(String symbol, LocalDate expiry) {
        RuntimeException lastException = null;
        for (OptionChainProvider provider : providers) {
            try {
                return provider.getOptionChain(symbol, expiry);
            } catch (Exception e) {
                lastException = new RuntimeException("Provider " + provider.getClass().getSimpleName() + " failed: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("All option chain providers failed for symbol " + symbol, lastException);
    }
}
