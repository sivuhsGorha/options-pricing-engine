package com.sbk.optionspricer.data;

import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.risk.VrpSignalBacktester;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Reads compressed option-chain snapshots recorded by {@link CompressedChainSnapshotRecorder}
 * back into typed objects, quotes, chains, and chronological {@link VrpSignalBacktester.Snapshot}
 * instances for backtesting and replay.
 */
public final class ChainSnapshotReader {

    private ChainSnapshotReader() {}

    /**
     * Reads all recorded quotes from a single .csv.gz file.
     */
    public static List<CompressedChainSnapshotRecorder.RecordedQuote> readQuotes(Path gzFile) throws IOException {
        if (gzFile == null || !Files.exists(gzFile)) {
            return List.of();
        }
        List<CompressedChainSnapshotRecorder.RecordedQuote> list = new ArrayList<>();
        try (GZIPInputStream gzis = new GZIPInputStream(Files.newInputStream(gzFile));
             BufferedReader reader = new BufferedReader(new InputStreamReader(gzis, StandardCharsets.UTF_8))) {

            String header = reader.readLine();
            if (header == null) {
                return List.of();
            }
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",", -1);
                if (parts.length < 14) continue;

                Instant ts = Instant.parse(parts[0]);
                String symbol = parts[1];
                String contractSymbol = parts[2];
                LocalDate expiry = LocalDate.parse(parts[3]);
                double strike = Double.parseDouble(parts[4]);
                String type = parts[5];
                double spot = Double.parseDouble(parts[6]);
                double bid = Double.parseDouble(parts[7]);
                double ask = Double.parseDouble(parts[8]);
                double mid = Double.parseDouble(parts[9]);
                double iv = Double.parseDouble(parts[10]);
                long vol = Long.parseLong(parts[11]);
                long oi = Long.parseLong(parts[12]);
                String source = parts[13];

                list.add(new CompressedChainSnapshotRecorder.RecordedQuote(
                        ts, symbol, contractSymbol, expiry, strike, type, spot, bid, ask, mid, iv, vol, oi, source
                ));
            }
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * Reconstructs OptionChain instances grouped by expiry from a single .csv.gz file.
     */
    public static List<OptionChain> readChains(Path gzFile) throws IOException {
        List<CompressedChainSnapshotRecorder.RecordedQuote> quotes = readQuotes(gzFile);
        if (quotes.isEmpty()) {
            return List.of();
        }
        String symbol = quotes.get(0).symbol();
        Map<LocalDate, List<OptionQuote>> byExpiry = new LinkedHashMap<>();
        Map<LocalDate, Double> spotByExpiry = new LinkedHashMap<>();

        for (CompressedChainSnapshotRecorder.RecordedQuote q : quotes) {
            OptionType type = "CALL".equalsIgnoreCase(q.type()) ? OptionType.CALL : OptionType.PUT;
            OptionQuote quote = new OptionQuote(
                    q.symbol(),
                    q.expiry(),
                    q.strike(),
                    type,
                    q.bid(),
                    q.ask(),
                    q.impliedVol(),
                    q.volume(),
                    q.openInterest()
            );
            byExpiry.computeIfAbsent(q.expiry(), k -> new ArrayList<>()).add(quote);
            spotByExpiry.putIfAbsent(q.expiry(), q.spot());
        }

        List<OptionChain> result = new ArrayList<>();
        for (Map.Entry<LocalDate, List<OptionQuote>> entry : byExpiry.entrySet()) {
            double spot = spotByExpiry.getOrDefault(entry.getKey(), 100.0);
            result.add(new OptionChain(symbol, entry.getKey(), spot, entry.getValue()));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Lists all .csv.gz snapshot files for a given symbol and date, sorted chronologically.
     */
    public static List<Path> listDailySnapshotFiles(Path baseDir, String symbol, LocalDate date) throws IOException {
        if (baseDir == null || symbol == null || date == null) {
            return List.of();
        }
        Path dateDir = baseDir.resolve(symbol.toUpperCase(Locale.ROOT)).resolve(date.toString());
        if (!Files.exists(dateDir) || !Files.isDirectory(dateDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dateDir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".csv.gz"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        }
    }

    /**
     * Loads all chain snapshot files (.csv.gz) from a directory or single file, ordered by timestamp.
     *
     * @param rootOrFile path to a directory containing snapshots or a single .csv.gz file
     * @return chronological list of snapshots ready for replay
     * @throws IOException on I/O error
     */
    public static List<VrpSignalBacktester.Snapshot> loadSnapshots(Path rootOrFile) throws IOException {
        if (rootOrFile == null || !Files.exists(rootOrFile)) {
            return List.of();
        }

        List<Path> files;
        if (Files.isDirectory(rootOrFile)) {
            try (Stream<Path> stream = Files.walk(rootOrFile)) {
                files = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".csv.gz"))
                        .filter(p -> !p.getFileName().toString().contains(".tmp."))
                        .sorted()
                        .toList();
            }
        } else {
            files = List.of(rootOrFile);
        }

        List<VrpSignalBacktester.Snapshot> result = new ArrayList<>();
        for (Path file : files) {
            try {
                List<CompressedChainSnapshotRecorder.RecordedQuote> quotes = readQuotes(file);
                if (quotes.isEmpty()) continue;

                Map<ExpiryKey, List<OptionQuote>> quotesByChain = new LinkedHashMap<>();
                Map<ExpiryKey, Double> spotByChain = new HashMap<>();

                for (CompressedChainSnapshotRecorder.RecordedQuote q : quotes) {
                    OptionType type = "CALL".equalsIgnoreCase(q.type()) ? OptionType.CALL : OptionType.PUT;
                    OptionQuote quote = new OptionQuote(
                            q.symbol(),
                            q.expiry(),
                            q.strike(),
                            type,
                            q.bid(),
                            q.ask(),
                            q.impliedVol(),
                            q.volume(),
                            q.openInterest()
                    );
                    ExpiryKey key = new ExpiryKey(q.timestamp(), q.symbol(), q.expiry());
                    quotesByChain.computeIfAbsent(key, k -> new ArrayList<>()).add(quote);
                    spotByChain.putIfAbsent(key, q.spot());
                }

                for (Map.Entry<ExpiryKey, List<OptionQuote>> entry : quotesByChain.entrySet()) {
                    ExpiryKey key = entry.getKey();
                    double spot = spotByChain.getOrDefault(key, 100.0);
                    OptionChain chain = new OptionChain(key.symbol, key.expiry, spot, entry.getValue());
                    result.add(new VrpSignalBacktester.Snapshot(key.timestamp, chain));
                }
            } catch (Exception e) {
                System.err.println("[SNAPSHOT READER] Skipping corrupted file " + file + ": " + e.getMessage());
            }
        }

        result.sort(Comparator.comparing(VrpSignalBacktester.Snapshot::at));
        return List.copyOf(result);
    }

    private record ExpiryKey(Instant timestamp, String symbol, LocalDate expiry) {}
}
