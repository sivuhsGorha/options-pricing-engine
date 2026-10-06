package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.risk.GreekAlertManager;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Global, sticky trading halt. Once tripped, {@link OrderManager} rejects every new order until an
 * operator explicitly calls {@link #resume()}. The first halt reason is kept so the original cause
 * is not overwritten by follow-on alerts.
 */
public final class TradingHalt {

    public record Reason(String message, Instant at) {}

    private final AtomicReference<Reason> state = new AtomicReference<>();

    /** Trips the halt. Returns true if this call tripped it, false if it was already halted. */
    public boolean halt(String message) {
        String text = message == null || message.isBlank() ? "unspecified" : message;
        boolean tripped = state.compareAndSet(null, new Reason(text, Instant.now()));
        if (tripped) {
            System.err.println("[TRADING HALT] " + text);
        }
        return tripped;
    }

    public void resume() {
        if (state.getAndSet(null) != null) {
            System.err.println("[TRADING HALT] cleared by operator");
        }
    }

    public boolean isHalted() {
        return state.get() != null;
    }

    public Optional<Reason> reason() {
        return Optional.ofNullable(state.get());
    }

    /** Trips this halt whenever the manager raises a CRITICAL alert. */
    public void haltOnCriticalAlerts(GreekAlertManager alertManager) {
        if (alertManager == null) {
            throw new IllegalArgumentException("alertManager must not be null");
        }
        alertManager.addAlertListener(alert -> {
            if (alert.severity() == GreekAlertManager.AlertSeverity.CRITICAL) {
                halt(String.format(java.util.Locale.ROOT, "CRITICAL %s: %.2f >= %.2f",
                        alert.metric(), alert.value(), alert.threshold()));
            }
        });
    }
}
