package com.sbk.optionspricer.risk;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Authoritative append-only fill ledger for positions.
 * Ensures fills/order records persist for restart/reconciliation.
 */
public class FillLedger {
    private static final Path LEDGER_FILE = Paths.get("target", "fill_ledger.csv");
    
    static {
        init();
    }
    
    public static void init() {
        try {
            Files.createDirectories(LEDGER_FILE.getParent());
            if (!Files.exists(LEDGER_FILE)) {
                Files.writeString(LEDGER_FILE, "timestamp,symbol,executedQty,multiplier\n", StandardOpenOption.CREATE);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize FillLedger file", e);
        }
    }

    public static synchronized void recordFill(String symbol, int executedQty, int multiplier) {
        if (symbol == null || symbol.trim().isEmpty()) throw new IllegalArgumentException("Symbol cannot be null/empty");
        try (PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(LEDGER_FILE.toFile(), true)))) {
            out.printf(java.util.Locale.ROOT, "%s,%s,%d,%d%n", Instant.now().toString(), symbol, executedQty, multiplier);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write to FillLedger", e);
        }
    }
}
