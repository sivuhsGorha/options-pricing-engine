package com.sbk.optionspricer.market;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How old a price may be before it is STALE, which depends on where it came from: a streaming quote ages in seconds, a free delayed or end-of-day
 * feed runs minutes behind by design. The defaults are by status (LIVE 30 s, DELAYED 120 s); {@code STALE_LIMITS_SECONDS=POLYGON=15,FINNHUB=300}
 * overrides them per source. A malformed setting stops the start rather than being ignored.
 *
 * <p>This decides the STALE label. The order gate keeps its own cap on the age of a quote an order may rest on
 * ({@code OrderManager.MarketDataPolicy}), so a longer limit here widens what is displayed as usable, not what can trade.
 */
public final class StalenessPolicy {

    static final long LIVE_DEFAULT_MS = 30_000L;
    static final long DELAYED_DEFAULT_MS = 120_000L;
    private static final long MAX_SECONDS = 86_400L;

    private static volatile StalenessPolicy current = new StalenessPolicy(Map.of());

    private final Map<String, Long> overridesMs;

    private StalenessPolicy(Map<String, Long> overridesMs) {
        this.overridesMs = Map.copyOf(overridesMs);
    }

    public static StalenessPolicy defaults() {
        return new StalenessPolicy(Map.of());
    }

    /** The process-wide policy, set once at start by {@link #configure}; the defaults until then. */
    public static StalenessPolicy current() {
        return current;
    }

    public static void configure(StalenessPolicy policy) {
        current = policy == null ? defaults() : policy;
    }

    /** The policy of the environment's {@code STALE_LIMITS_SECONDS}; the defaults when it is empty or unset. */
    public static StalenessPolicy fromEnvironment(java.util.function.Function<String, String> environment) {
        return fromSpec(environment.apply("STALE_LIMITS_SECONDS"));
    }

    /** @param spec {@code SOURCE=seconds} pairs separated by commas; null or blank for no overrides */
    public static StalenessPolicy fromSpec(String spec) {
        Map<String, Long> overrides = new HashMap<>();
        if (spec != null && !spec.isBlank()) {
            for (String pair : spec.split(",")) {
                String[] parts = pair.trim().split("=", -1);
                if (parts.length != 2 || parts[0].isBlank()) {
                    throw new IllegalArgumentException("STALE_LIMITS_SECONDS must look like SOURCE=seconds,SOURCE=seconds");
                }
                long seconds;
                try {
                    seconds = Long.parseLong(parts[1].trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("STALE_LIMITS_SECONDS: the limit for " + parts[0].trim() + " is not a whole number of seconds");
                }
                if (seconds < 1 || seconds > MAX_SECONDS) {
                    throw new IllegalArgumentException("STALE_LIMITS_SECONDS: the limit for " + parts[0].trim() + " must be between 1 and " + MAX_SECONDS);
                }
                overrides.put(parts[0].trim().toUpperCase(Locale.ROOT), seconds * 1000L);
            }
        }
        return new StalenessPolicy(overrides);
    }

    /** The age beyond which a quote from {@code source} with this status is STALE. */
    public long limitMs(String source, MarketDataStatus status) {
        if (source != null) {
            Long override = overridesMs.get(source.toUpperCase(Locale.ROOT));
            if (override != null) {
                return override;
            }
        }
        return status == MarketDataStatus.LIVE ? LIVE_DEFAULT_MS : DELAYED_DEFAULT_MS;
    }
}
