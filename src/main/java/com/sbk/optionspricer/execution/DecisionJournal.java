package com.sbk.optionspricer.execution;

import java.io.BufferedWriter;
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
 * An append-only record of every decision the options strategy makes, one CSV row each, kept in the data volume so it
 * survives a rebuild (the console log does not) and is included in a backup. It is the raw material for a session
 * report and for any later test of whether a signal had an edge.
 *
 * <p>Writing is best effort by design: a journal that cannot be written is counted and reported once, and never stops
 * or slows a trading decision.
 */
public final class DecisionJournal {

    /** One recorded decision. Numbers are NaN where the decision had none (a wait has no volatility, for instance). */
    public record Row(Instant at, String action, String expiry, double strike, double marketIv, double referenceIv,
                      double edge, int straddleSign, String orders, String reason,
                      double spotAgeSeconds, double chainAgeSeconds, double optionQuoteAgeSeconds, double optionSpreadBps) {
        public Row(Instant at, String action, String expiry, double strike, double marketIv, double referenceIv,
                   double edge, int straddleSign, String orders, String reason) {
            this(at, action, expiry, strike, marketIv, referenceIv, edge, straddleSign, orders, reason,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }

        public Row(Instant at, String action, String expiry, double strike, double marketIv, double referenceIv,
                   double edge, int straddleSign, String orders, String reason,
                   double spotAgeSeconds, double chainAgeSeconds, double optionQuoteAgeSeconds) {
            this(at, action, expiry, strike, marketIv, referenceIv, edge, straddleSign, orders, reason,
                    spotAgeSeconds, chainAgeSeconds, optionQuoteAgeSeconds, Double.NaN);
        }
    }

    static final String HEADER = "at,action,expiry,strike,marketIv,referenceIv,edge,straddleSign,orders,reason,spotAgeSeconds,chainAgeSeconds,optionQuoteAgeSeconds,optionSpreadBps";

    private final Path file;
    private int failures;
    private boolean warned;

    public DecisionJournal(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        this.file = file;
    }

    public Path path() {
        return file;
    }

    /** How many decisions could not be written. */
    public synchronized int failures() {
        return failures;
    }

    public synchronized void record(VolSpreadStrategy.Decision d) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            boolean needHeader = !Files.exists(file) || Files.size(file) == 0;
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (needHeader) {
                    out.write(HEADER);
                    out.write('\n');
                }
                out.write(String.join(",",
                        d.at().toString(),
                        csv(d.action()),
                        d.expiry() == null ? "" : d.expiry().toString(),
                        number(d.strike(), "%.2f"),
                        number(d.marketIv(), "%.6f"),
                        number(d.referenceIv(), "%.6f"),
                        number(d.edge(), "%.6f"),
                        Integer.toString(d.straddleSign()),
                        csv(String.join("; ", d.orders())),
                        csv(d.reason() == null ? "" : d.reason()),
                        number(d.spotAgeSeconds(), "%.1f"),
                        number(d.chainAgeSeconds(), "%.1f"),
                        number(d.optionQuoteAgeSeconds(), "%.1f"),
                        number(d.optionSpreadBps(), "%.1f")));
                out.write('\n');
            }
        } catch (IOException | RuntimeException e) {
            failures++;
            if (!warned) {
                warned = true;
                System.err.println("[JOURNAL] cannot write " + file + ": " + e.getMessage() + " (decisions are not being recorded; trading is unaffected)");
            }
        }
    }

    private static String number(double v, String format) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, format, v) : "";
    }

    private static String csv(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        boolean quote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        return quote ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }

    /** Every row in the file, oldest first; none when the file does not exist. A row that cannot be read is skipped. */
    public static List<Row> read(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>();
        for (List<String> f : records(Files.readString(file, StandardCharsets.UTF_8))) {
            if (f.size() < 10 || "at".equals(f.get(0))) {
                continue;
            }
            try {
                double spotAge = f.size() > 10 ? parse(f.get(10)) : Double.NaN;
                double chainAge = f.size() > 11 ? parse(f.get(11)) : Double.NaN;
                double optionAge = f.size() > 12 ? parse(f.get(12)) : Double.NaN;
                double spreadBps = f.size() > 13 ? parse(f.get(13)) : Double.NaN;
                rows.add(new Row(Instant.parse(f.get(0)), f.get(1), f.get(2), parse(f.get(3)), parse(f.get(4)), parse(f.get(5)),
                        parse(f.get(6)), f.get(7).isBlank() ? 0 : Integer.parseInt(f.get(7).trim()), f.get(8), f.get(9),
                        spotAge, chainAge, optionAge, spreadBps));
            } catch (RuntimeException unreadable) {
                // a damaged row must not hide the others
            }
        }
        return List.copyOf(rows);
    }

    private static double parse(String s) {
        return s == null || s.isBlank() ? Double.NaN : Double.parseDouble(s.trim());
    }

    /** RFC 4180: fields separated by commas, quoted fields may hold commas, doubled quotes and line breaks. */
    private static List<List<String>> records(String text) {
        List<List<String>> out = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                fields.add(field.toString());
                field.setLength(0);
                if (!(fields.size() == 1 && fields.get(0).isEmpty())) {
                    out.add(fields);
                }
                fields = new ArrayList<>();
            } else if (c != '\r') {
                field.append(c);
            }
        }
        if (field.length() > 0 || !fields.isEmpty()) {
            fields.add(field.toString());
            out.add(fields);
        }
        return out;
    }
}
