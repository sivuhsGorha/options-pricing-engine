package com.sbk.optionspricer.data;

import com.sbk.optionspricer.OptionType;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Simple append-only historical store for option snapshots. This keeps the system
 * working without external DB dependencies while preserving a clean data model.
 */
public class HistoricalDataManager {
    private final Path baseDir;
    private final Path snapshotsFile;

    public HistoricalDataManager() {
        this(Path.of("data", "historical"));
    }

    public HistoricalDataManager(Path baseDir) {
        if (baseDir == null) {
            throw new IllegalArgumentException("baseDir must not be null");
        }
        this.baseDir = baseDir;
        this.snapshotsFile = baseDir.resolve("option_snapshots.csv");
        try {
            Files.createDirectories(baseDir);
            if (!Files.exists(snapshotsFile)) {
                Files.writeString(snapshotsFile,
                        "timestamp,symbol,expiry,strike,type,spot,bid,ask,impliedVolatility,volume,openInterest\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE
                );
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not initialize historical data storage", e);
        }
    }

    public synchronized void store(OptionSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }

        try (BufferedWriter writer = Files.newBufferedWriter(snapshotsFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(toCsv(snapshot));
            writer.newLine();
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist snapshot", e);
        }
    }

    public synchronized List<OptionSnapshot> loadAll() {
        if (!Files.exists(snapshotsFile)) {
            return List.of();
        }

        try {
            List<String> lines = Files.readAllLines(snapshotsFile, StandardCharsets.UTF_8);
            if (lines.size() <= 1) {
                return List.of();
            }

            List<OptionSnapshot> snapshots = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                snapshots.add(fromCsv(line));
            }
            return Collections.unmodifiableList(snapshots);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load historical snapshots", e);
        }
    }

    public synchronized List<OptionSnapshot> query(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }

        List<OptionSnapshot> result = new ArrayList<>();
        for (OptionSnapshot snapshot : loadAll()) {
            if (snapshot.symbol().equalsIgnoreCase(symbol)) {
                result.add(snapshot);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static String toCsv(OptionSnapshot snapshot) {
        return String.join(",",
                snapshot.timestamp().toString(),
                snapshot.symbol(),
                snapshot.expiry().toString(),
                Double.toString(snapshot.strike()),
                snapshot.type().name(),
                Double.toString(snapshot.spot()),
                Double.toString(snapshot.bid()),
                Double.toString(snapshot.ask()),
                Double.toString(snapshot.impliedVolatility()),
                Long.toString(snapshot.volume()),
                Long.toString(snapshot.openInterest())
        );
    }

    private static OptionSnapshot fromCsv(String line) {
        String[] parts = line.split(",", 11);
        if (parts.length != 11) {
            throw new IllegalArgumentException("Malformed snapshot record: " + line);
        }

        return new OptionSnapshot(
                Instant.parse(parts[0]),
                parts[1],
                LocalDate.parse(parts[2]),
                Double.parseDouble(parts[3]),
                OptionType.valueOf(parts[4]),
                Double.parseDouble(parts[5]),
                Double.parseDouble(parts[6]),
                Double.parseDouble(parts[7]),
                Double.parseDouble(parts[8]),
                Long.parseLong(parts[9]),
                Long.parseLong(parts[10])
        );
    }
}
