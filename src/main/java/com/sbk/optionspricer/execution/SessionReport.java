package com.sbk.optionspricer.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A plain-text account of one UTC day of the strategy's decisions, built from the {@link DecisionJournal}: what it did,
 * how often the data stopped it, how old the stale quotes were, where the record has holes, and what P&L and spot did.
 * It states what the journal holds and does not interpret it: a day with no rows is "no decisions recorded", never "quiet".
 */
public final class SessionReport {

    public record PnlSample(Instant at, double spot, double realized, double unrealized) {}

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Pattern FEED_AGE = Pattern.compile("feed (\\d+) s old");
    private static final Pattern VRP = Pattern.compile("VRP ([+-][0-9.]+) pts");
    /** The strategy steps every 30 s by default. */
    private static final long STEP_SECONDS = 30;
    private static final long GAP_SECONDS = 180;

    private SessionReport() {}

    public static String build(List<DecisionJournal.Row> allRows, List<PnlSample> allPnl, LocalDate day) {
        StringBuilder sb = new StringBuilder();
        sb.append("SESSION REPORT ").append(day).append(" (UTC day)\n");
        List<DecisionJournal.Row> rows = allRows.stream().filter(r -> LocalDate.ofInstant(r.at(), ZoneOffset.UTC).equals(day))
                .sorted(java.util.Comparator.comparing(DecisionJournal.Row::at)).toList();
        if (rows.isEmpty()) {
            return sb.append("  no decisions recorded for this day (the journal begins with the build that added it)\n").toString();
        }
        Instant first = rows.get(0).at();
        Instant last = rows.get(rows.size() - 1).at();
        long expected = Duration.between(first, last).getSeconds() / STEP_SECONDS + 1;
        long coverage = Math.min(100, Math.round(100.0 * rows.size() / expected));
        sb.append(String.format(Locale.ROOT, "  %d decisions from %s to %s UTC (coverage %d%% of the %d steps expected at %d s)%n",
                rows.size(), CLOCK.format(first), CLOCK.format(last), coverage, expected, STEP_SECONDS));

        int hold = 0, optionStale = 0, spotStale = 0, otherWaits = 0, otherDecisions = 0, ordersAttempted = 0, rvOnHolds = 0;
        List<Double> ages = new ArrayList<>();
        List<Double> edges = new ArrayList<>();
        List<Double> vrps = new ArrayList<>();
        List<Double> spotAges = new ArrayList<>();
        List<Double> chainAges = new ArrayList<>();
        List<Double> optionAges = new ArrayList<>();
        for (DecisionJournal.Row r : rows) {
            String reason = r.reason() == null ? "" : r.reason();
            if (Double.isFinite(r.spotAgeSeconds())) {
                spotAges.add(r.spotAgeSeconds());
            }
            if (Double.isFinite(r.chainAgeSeconds())) {
                chainAges.add(r.chainAgeSeconds());
            }
            if (Double.isFinite(r.optionQuoteAgeSeconds())) {
                optionAges.add(r.optionQuoteAgeSeconds());
            }
            if (r.orders() != null && !r.orders().isBlank()) {
                ordersAttempted++;
            }
            if ("HOLD".equals(r.action())) {
                hold++;
                if (Double.isFinite(r.edge())) {
                    edges.add(100.0 * r.edge());
                }
                Matcher v = VRP.matcher(reason);
                if (reason.contains("RV forecast") && v.find()) {
                    rvOnHolds++;
                    vrps.add(Double.parseDouble(v.group(1)));
                }
            } else if ("WAIT".equals(r.action())) {
                if (reason.startsWith("spot quote STALE")) {
                    spotStale++;
                } else if (reason.contains("quote STALE")) {
                    optionStale++;
                    Matcher m = FEED_AGE.matcher(reason);
                    if (m.find()) {
                        ages.add(Double.parseDouble(m.group(1)));
                    }
                } else {
                    otherWaits++;
                }
            } else {
                otherDecisions++;
            }
        }
        sb.append(String.format(Locale.ROOT, "  HOLD %d | option quote STALE %d | spot STALE %d | other waits %d | other decisions %d | orders attempted %d%n",
                hold, optionStale, spotStale, otherWaits, otherDecisions, ordersAttempted));
        if (!ages.isEmpty()) {
            DoubleSummaryStatistics s = ages.stream().mapToDouble(Double::doubleValue).summaryStatistics();
            sb.append(String.format(Locale.ROOT, "  feed age of stale option quotes: %d waits, %.0f to %.0f s, mean %.0f s%n",
                    ages.size(), s.getMin(), s.getMax(), s.getAverage()));
        }
        if (!spotAges.isEmpty() || !chainAges.isEmpty() || !optionAges.isEmpty()) {
            sb.append("  quote & chain age (s):");
            if (!spotAges.isEmpty()) {
                DoubleSummaryStatistics s = spotAges.stream().mapToDouble(Double::doubleValue).summaryStatistics();
                sb.append(String.format(Locale.ROOT, " spot mean %.1f (%.1f to %.1f)", s.getAverage(), s.getMin(), s.getMax()));
            }
            if (!chainAges.isEmpty()) {
                DoubleSummaryStatistics s = chainAges.stream().mapToDouble(Double::doubleValue).summaryStatistics();
                sb.append(String.format(Locale.ROOT, "%s chain mean %.1f (%.1f to %.1f)", !spotAges.isEmpty() ? " |" : "", s.getAverage(), s.getMin(), s.getMax()));
            }
            if (!optionAges.isEmpty()) {
                DoubleSummaryStatistics s = optionAges.stream().mapToDouble(Double::doubleValue).summaryStatistics();
                sb.append(String.format(Locale.ROOT, "%s option quote mean %.1f (%.1f to %.1f)", (!spotAges.isEmpty() || !chainAges.isEmpty()) ? " |" : "", s.getAverage(), s.getMin(), s.getMax()));
            }
            sb.append('\n');
        }
        int staleTotal = optionStale + spotStale;
        double stalePct = rows.isEmpty() ? 0.0 : (100.0 * staleTotal / rows.size());
        sb.append(String.format(Locale.ROOT, "  stale gate blocked: %d decisions (%.1f%% of steps)%n", staleTotal, stalePct));
        if (!edges.isEmpty()) {
            DoubleSummaryStatistics s = edges.stream().mapToDouble(Double::doubleValue).summaryStatistics();
            sb.append(String.format(Locale.ROOT, "  edge on holds (points): %+.2f to %+.2f, mean %+.2f%n", s.getMin(), s.getMax(), s.getAverage()));
        }
        if (hold > 0) {
            sb.append(String.format(Locale.ROOT, "  RV readout on %d of %d holds", rvOnHolds, hold));
            if (!vrps.isEmpty()) {
                DoubleSummaryStatistics s = vrps.stream().mapToDouble(Double::doubleValue).summaryStatistics();
                sb.append(String.format(Locale.ROOT, " (VRP %+.1f to %+.1f pts)", s.getMin(), s.getMax()));
            }
            sb.append('\n');
        }
        for (int i = 1; i < rows.size(); i++) {
            long seconds = Duration.between(rows.get(i - 1).at(), rows.get(i).at()).getSeconds();
            if (seconds > GAP_SECONDS) {
                sb.append(String.format(Locale.ROOT, "  gap of %.1f min from %s to %s UTC: the engine recorded nothing%n",
                        seconds / 60.0, CLOCK.format(rows.get(i - 1).at()), CLOCK.format(rows.get(i).at())));
            }
        }

        List<PnlSample> pnl = allPnl.stream().filter(p -> LocalDate.ofInstant(p.at(), ZoneOffset.UTC).equals(day))
                .sorted(java.util.Comparator.comparing(PnlSample::at)).toList();
        if (!pnl.isEmpty()) {
            PnlSample a = pnl.get(0);
            PnlSample b = pnl.get(pnl.size() - 1);
            double totalA = a.realized() + a.unrealized();
            double totalB = b.realized() + b.unrealized();
            sb.append(String.format(Locale.ROOT, "  P&L: %.2f to %.2f (%+.2f), spot %.2f to %.2f, from %d marks%n",
                    totalA, totalB, totalB - totalA, a.spot(), b.spot(), pnl.size()));
        }
        return sb.toString();
    }

    /** The P&L samples in {@code pnl_history.csv} (timestamp, spot, realizedPnl, unrealizedPnl); none if the file is absent. */
    public static List<PnlSample> readPnl(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return List.of();
        }
        List<PnlSample> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] f = line.split(",", -1);
            if (f.length < 4 || f[0].startsWith("timestamp")) {
                continue;
            }
            try {
                out.add(new PnlSample(Instant.parse(f[0].trim()), Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3])));
            } catch (RuntimeException unreadable) {
                // skip a damaged line
            }
        }
        return List.copyOf(out);
    }
}
