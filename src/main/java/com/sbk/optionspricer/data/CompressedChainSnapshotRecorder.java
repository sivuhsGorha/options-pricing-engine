package com.sbk.optionspricer.data;

import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionChainProvider;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.instruments.OccSymbol;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/**
 * High-frequency asynchronous chain-snapshot recorder.
 *
 * <p>Captures each minute's complete option chain (all listed expiries and strikes) and writes them to disk
 * compressed with GZIP (.csv.gz) on a dedicated background I/O thread. The hot path (pricing, calibration,
 * tick execution) merely enqueues an unmodifiable batch, ensuring disk I/O latency never stalls the system.
 *
 * <p>Partitioning structure:
 * <pre>{baseDir}/{SYMBOL}/{YYYY-MM-DD}/quotes_{HHmmss}.csv.gz</pre>
 * Writes are staged in a temporary file and committed atomically via {@link StandardCopyOption#ATOMIC_MOVE}
 * to prevent half-written or corrupted files upon crash.
 */
public final class CompressedChainSnapshotRecorder implements AutoCloseable {

    public static final String CSV_HEADER =
            "timestamp,symbol,contractSymbol,expiry,strike,type,spot,bid,ask,mid,impliedVol,volume,openInterest,source";

    private static final DateTimeFormatter DATE_DIR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FILE_FORMAT = DateTimeFormatter.ofPattern("HHmmss");

    public record RecordedQuote(
            Instant timestamp,
            String symbol,
            String contractSymbol,
            LocalDate expiry,
            double strike,
            String type,
            double spot,
            double bid,
            double ask,
            double mid,
            double impliedVol,
            long volume,
            long openInterest,
            String source
    ) {}

    private record SnapshotTask(
            String symbol,
            Instant timestamp,
            List<RecordedQuote> quotes,
            CountDownLatch completionLatch
    ) {}

    private final Path baseDir;
    private final Clock clock;
    private final BlockingQueue<SnapshotTask> queue;
    private final Thread worker;
    private final AtomicLong totalQuotesRecorded = new AtomicLong();
    private final AtomicLong totalFilesWritten = new AtomicLong();
    private final AtomicLong skippedUnchanged = new AtomicLong();
    private volatile Instant lastWrittenAt;
    /** The feed timestamps of the last batch that was queued; a batch with the same stamps carries nothing new. */
    private volatile List<Instant> lastFeedStamps = List.of();
    private volatile boolean closed;

    public CompressedChainSnapshotRecorder() {
        this(Path.of("data", "chain_snapshots"), Clock.systemUTC());
    }

    public CompressedChainSnapshotRecorder(Path baseDir) {
        this(baseDir, Clock.systemUTC());
    }

    public CompressedChainSnapshotRecorder(Path baseDir, Clock clock) {
        this(baseDir, clock, 2048);
    }

    public CompressedChainSnapshotRecorder(Path baseDir, Clock clock, int queueCapacity) {
        if (baseDir == null || clock == null) {
            throw new IllegalArgumentException("baseDir and clock must not be null");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.baseDir = baseDir;
        this.clock = clock;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create base directory for chain snapshots: " + baseDir, e);
        }

        this.worker = new Thread(this::runWorker, "chain-snapshot-recorder");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public Path baseDir() {
        return baseDir;
    }

    public long totalQuotesRecorded() {
        return totalQuotesRecorded.get();
    }

    public long totalFilesWritten() {
        return totalFilesWritten.get();
    }

    /** When a snapshot file was last completed, by this recorder's clock; empty before the first. */
    public java.util.Optional<Instant> lastWrittenAt() {
        return java.util.Optional.ofNullable(lastWrittenAt);
    }

    /** Batches left out because the feed had not moved since the last one that was kept. */
    public long skippedUnchanged() {
        return skippedUnchanged.get();
    }

    /**
     * Records a batch of option chains asynchronously. Returns true if queued, false if queue is full or closed.
     */
    public boolean record(List<OptionChain> chains, String source, Instant timestamp) {
        if (closed || chains == null || chains.isEmpty()) {
            return false;
        }
        Instant ts = timestamp != null ? timestamp : clock.instant();
        String symbol = chains.get(0).symbol().toUpperCase(Locale.ROOT);
        List<RecordedQuote> flattened = flatten(chains, source, ts);
        if (flattened.isEmpty()) {
            return false;
        }
        return queue.offer(new SnapshotTask(symbol, ts, flattened, null));
    }

    /**
     * Records sourced chains preserving each chain's individual provenance. A batch whose feed timestamps all equal
     * those of the previous kept batch is skipped (and returns false): overnight the provider serves one document
     * for hours. A chain without a feed timestamp has an unknown age, so such a batch is always kept.
     */
    public boolean recordSourced(List<OptionChainProvider.SourcedChain> sourcedChains, Instant timestamp) {
        if (closed || sourcedChains == null || sourcedChains.isEmpty()) {
            return false;
        }
        List<Instant> stamps = sourcedChains.stream().map(s -> s == null ? null : s.asOf()).toList();
        boolean allDated = stamps.stream().allMatch(java.util.Objects::nonNull);
        if (allDated && stamps.equals(lastFeedStamps)) {
            skippedUnchanged.incrementAndGet();
            return false;
        }
        Instant ts = timestamp != null ? timestamp : clock.instant();
        String symbol = sourcedChains.get(0).chain().symbol().toUpperCase(Locale.ROOT);
        List<RecordedQuote> flattened = new ArrayList<>();
        for (OptionChainProvider.SourcedChain sourced : sourcedChains) {
            if (sourced == null || sourced.chain() == null) continue;
            flattenChain(sourced.chain(), sourced.source(), ts, flattened);
        }
        if (flattened.isEmpty()) {
            return false;
        }
        boolean queued = queue.offer(new SnapshotTask(symbol, ts, List.copyOf(flattened), null));
        if (queued && allDated) {
            lastFeedStamps = stamps;
        }
        return queued;
    }

    /**
     * Flushes all currently pending write tasks and waits for completion (up to timeout).
     */
    public boolean flush(long timeout, TimeUnit unit) throws InterruptedException {
        if (closed) {
            return true;
        }
        CountDownLatch latch = new CountDownLatch(1);
        if (!queue.offer(new SnapshotTask(null, null, List.of(), latch), timeout, unit)) {
            return false;
        }
        return latch.await(timeout, unit);
    }

    private static List<RecordedQuote> flatten(List<OptionChain> chains, String source, Instant ts) {
        List<RecordedQuote> out = new ArrayList<>();
        String src = (source == null || source.isBlank()) ? "UNKNOWN" : source;
        for (OptionChain chain : chains) {
            if (chain == null) continue;
            flattenChain(chain, src, ts, out);
        }
        return List.copyOf(out);
    }

    private static void flattenChain(OptionChain chain, String source, Instant ts, List<RecordedQuote> out) {
        String sym = chain.symbol();
        LocalDate exp = chain.expiry();
        double spot = chain.spot();
        for (OptionQuote q : chain.quotes()) {
            String occ = null;
            try {
                occ = OccSymbol.format(sym, exp, q.type(), q.strike());
            } catch (RuntimeException ignored) {
                occ = sym + "_" + exp + "_" + q.type() + "_" + q.strike();
            }
            out.add(new RecordedQuote(
                    ts,
                    sym,
                    occ,
                    exp,
                    q.strike(),
                    q.type().name(),
                    spot,
                    q.bid(),
                    q.ask(),
                    q.midPrice(),
                    q.impliedVolatility(),
                    q.volume(),
                    q.openInterest(),
                    source
            ));
        }
    }

    private void runWorker() {
        while (!closed || !queue.isEmpty()) {
            try {
                SnapshotTask task = queue.poll(200, TimeUnit.MILLISECONDS);
                if (task == null) {
                    continue;
                }
                if (task.completionLatch != null) {
                    task.completionLatch.countDown();
                    continue;
                }
                writeCompressedSnapshot(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                System.err.println("[CHAIN RECORDER] Error writing compressed snapshot: " + t.getMessage());
            }
        }
    }

    private void writeCompressedSnapshot(SnapshotTask task) {
        if (task.quotes.isEmpty() || task.symbol == null) {
            return;
        }
        LocalDate date = LocalDate.ofInstant(task.timestamp, ZoneOffset.UTC);
        String dateStr = DATE_DIR_FORMAT.format(date);
        String timeStr = TIME_FILE_FORMAT.format(task.timestamp.atZone(ZoneOffset.UTC));

        Path targetDir = baseDir.resolve(task.symbol).resolve(dateStr);
        try {
            Files.createDirectories(targetDir);
            Path finalFile = targetDir.resolve("quotes_" + timeStr + ".csv.gz");
            Path tempFile = targetDir.resolve("quotes_" + timeStr + ".tmp." + System.nanoTime() + ".gz");

            try (GZIPOutputStream gzos = new GZIPOutputStream(Files.newOutputStream(tempFile), 65536);
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(gzos, StandardCharsets.UTF_8), 65536)) {

                writer.write(CSV_HEADER);
                writer.newLine();

                for (RecordedQuote q : task.quotes) {
                    writer.write(String.format(Locale.ROOT,
                            "%s,%s,%s,%s,%.4f,%s,%.4f,%.4f,%.4f,%.4f,%.6f,%d,%d,%s",
                            q.timestamp(),
                            q.symbol(),
                            q.contractSymbol(),
                            q.expiry(),
                            q.strike(),
                            q.type(),
                            q.spot(),
                            q.bid(),
                            q.ask(),
                            q.mid(),
                            q.impliedVol(),
                            q.volume(),
                            q.openInterest(),
                            q.source()));
                    writer.newLine();
                }
            }

            try {
                Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailed) {
                Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
            }

            totalQuotesRecorded.addAndGet(task.quotes.size());
            totalFilesWritten.incrementAndGet();
            lastWrittenAt = clock.instant();
        } catch (IOException e) {
            System.err.println("[CHAIN RECORDER] Failed to write snapshot for " + task.symbol + " at " + task.timestamp + ": " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        // Drain first: flush() is a no-op once closed is set, and interrupting the worker abandons whatever is queued.
        try {
            flush(5, TimeUnit.SECONDS);
            closed = true;
            worker.join(5_000L);
        } catch (InterruptedException ignored) {
            closed = true;
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            worker.interrupt();
        }
    }
}
