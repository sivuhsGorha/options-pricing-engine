package com.sbk.optionspricer.risk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Append-only fill ledger: the authoritative record of what was traded, from which positions, average cost
 * and realised P&L are rebuilt at start. One CSV row per fill ({@code timestamp,symbol,executedQty,multiplier,
 * price}) under the data directory, never under {@code target/} (which a clean build deletes).
 *
 * <p>Writes go to disk before the in-memory book changes. A row that cannot be parsed stops the replay with
 * its line number rather than being skipped: a ledger with a hole in it must not quietly produce a different
 * book.
 */
public final class FillLedger implements FillRecorder {

    public record Fill(Instant at, String symbol, int quantity, int multiplier, double price) {}

    private static final String HEADER = "timestamp,symbol,executedQty,multiplier,price";

    private final Path file;

    public FillLedger(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("ledger file must not be null");
        }
        this.file = file;
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            if (!Files.exists(file)) {
                Files.writeString(file, HEADER + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot initialise the fill ledger at " + file + ": " + e.getMessage(), e);
        }
    }

    public Path path() {
        return file;
    }

    @Override
    public synchronized void record(String symbol, int executedQty, int multiplier, double price) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (executedQty == 0 || multiplier <= 0 || !Double.isFinite(price) || price <= 0.0) {
            throw new IllegalArgumentException("a fill needs a non-zero quantity, a positive multiplier and a positive price");
        }
        String row = String.format(Locale.ROOT, "%s,%s,%d,%d,%.6f%n", Instant.now(), symbol.trim().toUpperCase(Locale.ROOT), executedQty, multiplier, price);
        try {
            Files.writeString(file, row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("cannot append to the fill ledger at " + file + ": " + e.getMessage(), e);
        }
    }

    /** Every fill in the ledger, oldest first. */
    public synchronized List<Fill> readAll() {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the fill ledger at " + file + ": " + e.getMessage(), e);
        }
        List<Fill> fills = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || (i == 0 && line.startsWith("timestamp"))) {
                continue;
            }
            String[] parts = line.split(",");
            if (parts.length != 5) {
                throw new IllegalStateException(file.getFileName() + " line " + (i + 1) + ": expected 5 fields, found " + parts.length + ": " + line);
            }
            try {
                fills.add(new Fill(Instant.parse(parts[0].trim()), parts[1].trim(), Integer.parseInt(parts[2].trim()),
                        Integer.parseInt(parts[3].trim()), Double.parseDouble(parts[4].trim())));
            } catch (RuntimeException bad) {
                throw new IllegalStateException(file.getFileName() + " line " + (i + 1) + ": " + bad.getMessage() + ": " + line, bad);
            }
        }
        return List.copyOf(fills);
    }
}
