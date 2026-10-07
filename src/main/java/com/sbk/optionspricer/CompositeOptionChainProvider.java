package com.sbk.optionspricer;

import java.time.LocalDate;
import java.util.List;

/**
 * Tries each provider in order and returns the first chain. {@link #getSourcedChain} names the provider that
 * actually answered, so a caller can tell a Yahoo chain from the synthetic fallback behind it.
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
        return getSourcedChain(symbol, expiry).chain();
    }

    @Override
    public SourcedChain getSourcedChain(String symbol, LocalDate expiry) {
        RuntimeException lastException = null;
        for (OptionChainProvider provider : providers) {
            try {
                return provider.getSourcedChain(symbol, expiry);
            } catch (Exception e) {
                lastException = new RuntimeException("Provider " + provider.sourceName() + " failed: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("All option chain providers failed for symbol " + symbol, lastException);
    }

    /** The listing of the first provider that has one; the monthly default if none does. */
    @Override
    public List<LocalDate> listExpiries(String symbol, LocalDate asOf) {
        for (OptionChainProvider provider : providers) {
            try {
                List<LocalDate> listed = provider.listExpiries(symbol, asOf);
                if (listed != null && !listed.isEmpty()) {
                    return listed;
                }
            } catch (RuntimeException ignored) {
                // this provider cannot list; the next one may
            }
        }
        return OptionChainProvider.super.listExpiries(symbol, asOf);
    }

    @Override
    public String sourceName() {
        return "COMPOSITE";
    }

    @Override
    public boolean isMarketData() {
        return providers.stream().anyMatch(OptionChainProvider::isMarketData);
    }
}
