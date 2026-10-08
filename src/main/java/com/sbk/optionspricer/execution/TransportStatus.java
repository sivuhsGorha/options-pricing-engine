package com.sbk.optionspricer.execution;

import java.time.Instant;
import java.util.List;

/**
 * What the dashboard says about where orders go.
 *
 * @param transport      {@code paper} (the in-process simulator) or {@code alpaca} (the Alpaca paper account)
 * @param venue          where fills happen, for the operator
 * @param description    how an order is handled there, in one sentence
 * @param checkedAt      when the book was last reconciled with the venue; null when there is nothing to reconcile
 * @param reconciliation {@code NOT_APPLICABLE}, {@code PENDING}, {@code OK}, {@code MISMATCH} or {@code UNREACHABLE}
 * @param differences    one line per position that differs, empty otherwise
 */
public record TransportStatus(String transport, String venue, String description, Instant checkedAt,
                              String reconciliation, List<String> differences) {

    public static TransportStatus paper(double slippageBps) {
        return new TransportStatus("paper", "in-process simulator",
                String.format(java.util.Locale.ROOT, "every order fills in full at the touch moved %.0f bps against you; nothing leaves the process", slippageBps),
                null, "NOT_APPLICABLE", List.of());
    }
}
