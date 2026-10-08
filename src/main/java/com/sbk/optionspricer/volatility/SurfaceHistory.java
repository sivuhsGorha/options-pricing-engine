package com.sbk.optionspricer.volatility;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Consumer;

/**
 * Append-only record of every calibration, one CSV row per fitted model, so the surface can be charted over
 * the day and the history survives a restart. Each row keeps the front-expiry at-the-money vol and skew, the
 * RMSE, the quotes used, the provenance and the fitted parameters.
 *
 * <p>ATM vol is the fitted vol at strike = spot on the shortest fitted expiry (spot, not the forward: the
 * difference is a few basis points of strike at one month). Skew is vol(0.95 spot) minus vol(1.05 spot) on
 * that expiry, positive when downside protection is dearer, as it usually is for an index. Both are read off
 * the fitted grid, so they describe the model, not one quote. A row that cannot be parsed stops the load and
 * names the line rather than being skipped.
 */
public final class SurfaceHistory implements Consumer<VolatilitySurfaceSource.Snapshot> {

    /** One calibration of one model. {@code atmVol} and {@code skew} are NaN when the grid does not cover them. */
    public record Entry(Instant at, String symbol, String source, boolean marketData, double spot, String model,
                        int quotesUsed, double rmse, double frontExpiry, double atmVol, double skew, Map<String, Double> parameters) {}

    static final double SKEW_LOW_MONEYNESS = 0.95;
    static final double SKEW_HIGH_MONEYNESS = 1.05;
    /** The front slice is matched exactly; this tolerance only absorbs rounding. */
    private static final double SLICE_MATCH_DAYS = 0.5;
    /** Entries kept in memory for the API; the file keeps everything. */
    static final int MAX_IN_MEMORY = 20_000;
    private static final String HEADER = "timestamp,symbol,source,marketData,spot,model,quotesUsed,rmse,frontExpiry,atmVol,skew,parameters";
    private static final int FIELDS = 12;

    private final Path file;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    public SurfaceHistory(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("history file must not be null");
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
            throw new IllegalStateException("cannot initialise the surface history at " + file + ": " + e.getMessage(), e);
        }
        for (Entry entry : load()) {
            remember(entry);
        }
    }

    public Path path() {
        return file;
    }

    /** Entries in memory (at most {@link #MAX_IN_MEMORY}, the newest). */
    public synchronized int size() {
        return entries.size();
    }

    @Override
    public void accept(VolatilitySurfaceSource.Snapshot snapshot) {
        record(snapshot);
    }

    /** Writes one row per fitted model of the snapshot and returns what was written. */
    public synchronized List<Entry> record(VolatilitySurfaceSource.Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        List<Entry> written = new ArrayList<>(3);
        for (SurfaceFitter.Fit fit : new SurfaceFitter.Fit[] {snapshot.ssvi(), snapshot.svi(), snapshot.sabr()}) {
            if (fit == null) {
                continue;
            }
            Entry entry = summarise(snapshot, fit);
            append(entry);
            remember(entry);
            written.add(entry);
        }
        return written;
    }

    /** Entries at or after {@code from}, oldest first; {@code model} null means every model. */
    public synchronized List<Entry> since(Instant from, String model) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if ((from == null || !e.at().isBefore(from)) && (model == null || model.equals(e.model()))) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    /** The front-expiry ATM vol and skew of one fit, read off its grid. */
    static Entry summarise(VolatilitySurfaceSource.Snapshot snapshot, SurfaceFitter.Fit fit) {
        double[] fitted = fit.fittedExpiries();
        double front;
        if (fitted != null && fitted.length > 0) {
            front = fitted[0];
            for (double t : fitted) front = Math.min(front, t);
        } else {
            front = fit.expiries()[0];
        }
        double spot = snapshot.spot();
        OptionalDouble atm = SurfaceFitter.volAt(fit, front, spot, SLICE_MATCH_DAYS);
        OptionalDouble low = SurfaceFitter.volAt(fit, front, SKEW_LOW_MONEYNESS * spot, SLICE_MATCH_DAYS);
        OptionalDouble high = SurfaceFitter.volAt(fit, front, SKEW_HIGH_MONEYNESS * spot, SLICE_MATCH_DAYS);
        double skew = low.isPresent() && high.isPresent() ? low.getAsDouble() - high.getAsDouble() : Double.NaN;
        return new Entry(snapshot.asOf(), snapshot.symbol(), snapshot.source(), snapshot.marketData(), spot, fit.model(),
                fit.quotesUsed(), fit.rmse(), front, atm.isPresent() ? atm.getAsDouble() : Double.NaN, skew,
                fit.parameters() == null ? Map.of() : Map.copyOf(fit.parameters()));
    }

    private void remember(Entry entry) {
        entries.addLast(entry);
        while (entries.size() > MAX_IN_MEMORY) {
            entries.removeFirst();
        }
    }

    private void append(Entry e) {
        StringBuilder params = new StringBuilder();
        for (Map.Entry<String, Double> p : new java.util.TreeMap<>(e.parameters()).entrySet()) {
            if (params.length() > 0) params.append(';');
            params.append(p.getKey().replaceAll("[,;=\\r\\n]", "_")).append('=').append(Double.toString(p.getValue()));
        }
        String row = String.join(",", e.at().toString(), e.symbol(), e.source().replace(',', '_'), Boolean.toString(e.marketData()),
                Double.toString(e.spot()), e.model(), Integer.toString(e.quotesUsed()), Double.toString(e.rmse()),
                Double.toString(e.frontExpiry()), Double.toString(e.atmVol()), Double.toString(e.skew()), params.toString())
                + System.lineSeparator();
        try {
            Files.writeString(file, row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot append to the surface history at " + file + ": " + ex.getMessage(), ex);
        }
    }

    private List<Entry> load() {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the surface history at " + file + ": " + e.getMessage(), e);
        }
        List<Entry> out = new ArrayList<>();
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
                Map<String, Double> parameters = new LinkedHashMap<>();
                if (!f[11].isBlank()) {
                    for (String kv : f[11].split(";")) {
                        int eq = kv.indexOf('=');
                        if (eq <= 0) throw new IllegalArgumentException("bad parameter '" + kv + "'");
                        parameters.put(kv.substring(0, eq), Double.parseDouble(kv.substring(eq + 1)));
                    }
                }
                out.add(new Entry(Instant.parse(f[0]), f[1], f[2], Boolean.parseBoolean(f[3]), Double.parseDouble(f[4]), f[5],
                        Integer.parseInt(f[6]), Double.parseDouble(f[7]), Double.parseDouble(f[8]), Double.parseDouble(f[9]),
                        Double.parseDouble(f[10]), Map.copyOf(parameters)));
            } catch (RuntimeException bad) {
                throw new IllegalStateException(file.getFileName() + " line " + (i + 1) + ": " + bad.getMessage() + ": " + line, bad);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "SurfaceHistory[%s, %d entries]", file, size());
    }
}
