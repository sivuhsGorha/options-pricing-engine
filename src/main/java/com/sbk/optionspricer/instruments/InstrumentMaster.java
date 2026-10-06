package com.sbk.optionspricer.instruments;

import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory catalog for option contracts derived from option chains.
 */
public class InstrumentMaster {
    private final Map<String, Map<LocalDate, Map<Double, Map<OptionType, Instrument>>>> instruments = new ConcurrentHashMap<>();

    public void upsert(Instrument instrument) {
        if (instrument == null) {
            throw new IllegalArgumentException("instrument must not be null");
        }

        instruments.computeIfAbsent(instrument.symbol(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(instrument.expiry(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(instrument.strike(), ignored -> new ConcurrentHashMap<>())
                .put(instrument.type(), instrument);
    }

    public Optional<Instrument> get(String symbol, LocalDate expiry, double strike, OptionType type) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (expiry == null) {
            throw new IllegalArgumentException("expiry must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }

        Map<LocalDate, Map<Double, Map<OptionType, Instrument>>> byExpiry = instruments.get(symbol);
        if (byExpiry == null) {
            return Optional.empty();
        }
        Map<Double, Map<OptionType, Instrument>> byStrike = byExpiry.get(expiry);
        if (byStrike == null) {
            return Optional.empty();
        }
        Map<OptionType, Instrument> byType = byStrike.get(strike);
        if (byType == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byType.get(type));
    }

    public List<Instrument> getForSymbol(String symbol) {
        Map<LocalDate, Map<Double, Map<OptionType, Instrument>>> byExpiry = instruments.get(symbol);
        if (byExpiry == null || byExpiry.isEmpty()) {
            return List.of();
        }

        List<Instrument> all = new ArrayList<>();
        byExpiry.values().forEach(strikes -> strikes.values().forEach(types -> all.addAll(types.values())));
        return Collections.unmodifiableList(all);
    }

    public List<Instrument> getForExpiry(String symbol, LocalDate expiry) {
        Map<LocalDate, Map<Double, Map<OptionType, Instrument>>> byExpiry = instruments.get(symbol);
        if (byExpiry == null) {
            return List.of();
        }
        Map<Double, Map<OptionType, Instrument>> byStrike = byExpiry.get(expiry);
        if (byStrike == null || byStrike.isEmpty()) {
            return List.of();
        }

        List<Instrument> result = new ArrayList<>();
        byStrike.values().forEach(types -> result.addAll(types.values()));
        return Collections.unmodifiableList(result);
    }

    public void updateFromChain(OptionChain chain) {
        if (chain == null) {
            throw new IllegalArgumentException("chain must not be null");
        }
        for (OptionQuote quote : chain.quotes()) {
            upsert(new Instrument(
                    quote.symbol(),
                    quote.expiry(),
                    quote.strike(),
                    quote.type(),
                    100.0,
                    "09:30-16:00",
                    0.01,
                    true
            ));
        }
    }

    public void markExpiredInactive(LocalDate asOf) {
        for (Map<LocalDate, Map<Double, Map<OptionType, Instrument>>> byExpiry : instruments.values()) {
            for (Map.Entry<LocalDate, Map<Double, Map<OptionType, Instrument>>> entry : byExpiry.entrySet()) {
                if (entry.getKey().isBefore(asOf)) {
                    for (Map<OptionType, Instrument> byType : entry.getValue().values()) {
                        for (Map.Entry<OptionType, Instrument> instrumentEntry : byType.entrySet()) {
                            Instrument active = instrumentEntry.getValue();
                            byType.put(instrumentEntry.getKey(), new Instrument(
                                    active.symbol(),
                                    active.expiry(),
                                    active.strike(),
                                    active.type(),
                                    active.multiplier(),
                                    active.tradingHours(),
                                    active.minTick(),
                                    false
                            ));
                        }
                    }
                }
            }
        }
    }

    public int size() {
        int total = 0;
        for (Map<LocalDate, Map<Double, Map<OptionType, Instrument>>> byExpiry : instruments.values()) {
            for (Map<Double, Map<OptionType, Instrument>> byStrike : byExpiry.values()) {
                for (Map<OptionType, Instrument> byType : byStrike.values()) {
                    total += byType.size();
                }
            }
        }
        return total;
    }
}
