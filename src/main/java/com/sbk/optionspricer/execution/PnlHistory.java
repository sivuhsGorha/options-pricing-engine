package com.sbk.optionspricer.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Append-only record of the book's P&L over time, sampled from the valuation service, so the dashboard can
 * show the day's P&L and drawdown and the figures survive a restart. One CSV row per sample: time, spot,
 * realised and unrealised P&L. A sample is written at most every {@code minInterval} unless realised P&L
 * changed (a fill closed something), which is always recorded.
 *
 * <p>Realised and unrealised P&L are cumulative from a flat book, because the tracker is rebuilt from the
 * fill ledger at start; their sum is therefore the P&L since the ledger began. The trading day is the
 * calendar day in {@link #TRADING_DAY_ZONE} (US listed options). Today's P&L is measured from the last sample
 * before the day started (yesterday's last mark) or, when there is none, from the first sample of the day;
 * the day's maximum drawdown is the largest peak-to-trough fall of total P&L over that series.
 */
public final class PnlHistory implements Consumer<Valuation> {

    public static final ZoneId TRADING_DAY_ZONE = ZoneId.of("America/New_York");
    /** Samples kept in memory for the API; the file keeps everything. */
    static final int MAX_IN_MEMORY = 200_000;
    private static final String HEADER = "timestamp,spot,realizedPnl,unrealizedPnl";
    private static final int FIELDS = 4;

    public record Sample(Instant at, double spot, double realized, double unrealized) {
        public double total() {
            return realized + unrealized;
        }
    }

    /**
     * @param dayBaseline            total P&L today is measured from
     * @param baselineIsPreviousClose true when the baseline is the last sample before the day started
     * @param dayMaxDrawdown         largest fall from a running peak of total P&L today, never negative
     */
    public record Summary(Instant asOf, Instant firstSampleAt, double realized, double unrealized, double total,
                          LocalDate day, Instant dayStart, double dayBaseline, boolean baselineIsPreviousClose,
                          double dayPnl, double dayPeak, double dayTrough, double dayMaxDrawdown, List<Sample> today) {}

    private final Path file;
    private final Duration minInterval;
    private final List<Sample> samples = new ArrayList<>();
    private Instant firstSampleAt;

    public PnlHistory(Path file, Duration minInterval) {
        if (file == null || minInterval == null || minInterval.isNegative()) {
            throw new IllegalArgumentException("file must not be null and minInterval must not be negative");
        }
        this.file = file;
        this.minInterval = minInterval;
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            if (!Files.exists(file)) {
                Files.writeString(file, HEADER + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot initialise the P&L history at " + file + ": " + e.getMessage(), e);
        }
        for (Sample s : load()) {
            remember(s);
        }
    }

    public Path path() {
        return file;
    }

    public synchronized int size() {
        return samples.size();
    }

    @Override
    public void accept(Valuation valuation) {
        record(valuation);
    }

    /** Records the valuation if it is due; returns whether a row was written. */
    public synchronized boolean record(Valuation v) {
        if (v == null || !Double.isFinite(v.realizedPnl()) || !Double.isFinite(v.unrealizedPnl())) {
            return false;
        }
        Sample last = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        if (last != null) {
            if (v.asOf().isBefore(last.at())) {
                return false; // out of order (clock moved back): keep the file monotonic
            }
            boolean due = !v.asOf().isBefore(last.at().plus(minInterval));
            boolean realisedChanged = v.realizedPnl() != last.realized();
            if (!due && !realisedChanged) {
                return false;
            }
        }
        Sample sample = new Sample(v.asOf(), v.spot(), v.realizedPnl(), v.unrealizedPnl());
        append(sample);
        remember(sample);
        return true;
    }

    /** The day's P&L and drawdown as of {@code now}; empty while nothing has been sampled. */
    public synchronized Optional<Summary> summary(Instant now) {
        if (samples.isEmpty()) {
            return Optional.empty();
        }
        Sample last = samples.get(samples.size() - 1);
        LocalDate day = LocalDate.ofInstant(now, TRADING_DAY_ZONE);
        Instant dayStart = day.atStartOfDay(TRADING_DAY_ZONE).toInstant();

        Sample previousClose = null;
        List<Sample> today = new ArrayList<>();
        for (Sample s : samples) {
            if (s.at().isBefore(dayStart)) {
                previousClose = s;
            } else {
                today.add(s);
            }
        }
        double baseline;
        boolean fromPreviousClose;
        if (previousClose != null) {
            baseline = previousClose.total();
            fromPreviousClose = true;
        } else if (!today.isEmpty()) {
            baseline = today.get(0).total();
            fromPreviousClose = false;
        } else {
            baseline = last.total();
            fromPreviousClose = false;
        }
        double peak = baseline, trough = baseline, runningPeak = baseline, maxDrawdown = 0.0;
        for (Sample s : today) {
            double total = s.total();
            runningPeak = Math.max(runningPeak, total);
            maxDrawdown = Math.max(maxDrawdown, runningPeak - total);
            peak = Math.max(peak, total);
            trough = Math.min(trough, total);
        }
        return Optional.of(new Summary(last.at(), firstSampleAt, last.realized(), last.unrealized(), last.total(),
                day, dayStart, baseline, fromPreviousClose, last.total() - baseline, peak, trough, maxDrawdown, List.copyOf(today)));
    }

    private void remember(Sample s) {
        if (firstSampleAt == null) {
            firstSampleAt = s.at();
        }
        samples.add(s);
        if (samples.size() > MAX_IN_MEMORY) {
            samples.subList(0, samples.size() - MAX_IN_MEMORY).clear();
        }
    }

    private void append(Sample s) {
        String row = String.join(",", s.at().toString(), Double.toString(s.spot()), Double.toString(s.realized()), Double.toString(s.unrealized()))
                + System.lineSeparator();
        try {
            Files.writeString(file, row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("cannot append to the P&L history at " + file + ": " + e.getMessage(), e);
        }
    }

    private List<Sample> load() {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the P&L history at " + file + ": " + e.getMessage(), e);
        }
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || (i == 0 && line.startsWith("timestamp"))) {
                continue;
            }
            String[] f = line.split(",", -1);
            if (f.length != FIELDS) {
                throw new IllegalStateException(file.getFileName() + " line " + (i + 1) + ": expected " + FIELDS + " fields, found " + f.length + ": " + line);
            }
            try {
                out.add(new Sample(Instant.parse(f[0]), Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3])));
            } catch (RuntimeException bad) {
                throw new IllegalStateException(file.getFileName() + " line " + (i + 1) + ": " + bad.getMessage() + ": " + line, bad);
            }
        }
        return out;
    }
}
