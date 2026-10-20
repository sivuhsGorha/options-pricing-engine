package com.sbk.optionspricer;

import com.sbk.optionspricer.config.ConfigManager;
import com.sbk.optionspricer.config.ConfigValidator;
import com.sbk.optionspricer.core.MmapStatePublisher;
import com.sbk.optionspricer.core.QuantSimulationHarness;
import com.sbk.optionspricer.core.UnifiedQuantEngine;
import com.sbk.optionspricer.data.OptionSnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Unified Main Entry Point for Blackthorn Sterling, the options pricing and paper-trading engine.
 * Executes core pricing models, benchmark suites, and launches the unified
 * real-time cross-functional quantitative engine.
 */
public class Main {

    /**
     * One-line Black-Scholes demonstration. It reports C - P next to S*exp(-qT) - K*exp(-rT): the two must be equal,
     * and their difference is the put-call parity error (rounding noise, ~1e-14). The old line printed C - P under the
     * label "Parity", which looked like a violation whenever it was non-zero, but C - P is not zero (here it is -2.4075).
     */
    public static String pricingSummary(double spot, double strike, double expiry, double rate, double vol, double dividendYield) {
        double call = BlackScholesPricer.price(OptionType.CALL, spot, strike, expiry, rate, vol, dividendYield);
        double put = BlackScholesPricer.price(OptionType.PUT, spot, strike, expiry, rate, vol, dividendYield);
        double forwardGap = spot * Math.exp(-dividendYield * expiry) - strike * Math.exp(-rate * expiry);
        return String.format(java.util.Locale.ROOT,
                "Black-Scholes Call: %.4f | Put: %.4f | C - P = %.4f | S*exp(-qT) - K*exp(-rT) = %.4f | parity error = %.3E",
                call, put, call - put, forwardGap, (call - put) - forwardGap);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--check-config".equals(args[0])) {
            System.exit(com.sbk.optionspricer.config.ConfigCheck.run(java.nio.file.Path.of(ConfigManager.DEFAULT_CONFIG_PATH),
                    com.sbk.optionspricer.config.EnvironmentConfigLoader::get, System.out));
            return;
        }
        if (args.length > 0 && ("--replay".equals(args[0]) || "--replay-vrp".equals(args[0]))) {
            java.nio.file.Path targetDir = args.length > 1
                    ? java.nio.file.Path.of(args[1])
                    : java.nio.file.Path.of("data", "chain_snapshots");
            System.exit(runReplay(targetDir));
            return;
        }
        if (args.length > 0 && "--session-report".equals(args[0])) {
            java.time.LocalDate day = null;
            if (args.length > 1) {
                try {
                    day = java.time.LocalDate.parse(args[1]);
                } catch (java.time.format.DateTimeParseException e) {
                    System.err.println("[REPORT] Give the day as YYYY-MM-DD (UTC), for example --session-report 2026-10-15");
                    System.exit(2);
                    return;
                }
            }
            System.exit(runSessionReport(java.nio.file.Path.of(com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("DATA_DIR", "data")), day));
            return;
        }
        if (args.length > 0 && "--vrp-study".equals(args[0])) {
            System.exit(runVrpStudy(java.nio.file.Path.of(args.length > 1 ? args[1] : "data/historical/VIX_History.csv")));
            return;
        }
        if (args.length > 0 && "--forecast-eval".equals(args[0])) {
            System.exit(runForecastEval());
            return;
        }
        if (args.length > 0 && "--test-buy-straddle".equals(args[0])) {
            System.exit(runTestBuyStraddle(args.length > 1 && "--confirm".equals(args[1])));
            return;
        }
        System.out.println("=========================================================================");
        System.out.println("       BLACKTHORN STERLING  OPTIONS PRICING & PAPER-TRADING ENGINE       ");
        System.out.println("=========================================================================");

        ConfigManager config;
        List<String> validationErrors;
        try {
            config = new ConfigManager();
            validationErrors = ConfigValidator.validate(config);
        } catch (com.sbk.optionspricer.config.ConfigException e) {
            // A configuration mistake is an operator message, not a stack trace.
            System.err.println("[CONFIG] " + e.getMessage());
            System.err.println("[CONFIG] Fix " + ConfigManager.DEFAULT_CONFIG_PATH + " and start again; `--check-config` reports every problem at once.");
            System.exit(2);
            return;
        }
        if (!validationErrors.isEmpty()) {
            System.err.println("[CONFIG] " + ConfigManager.DEFAULT_CONFIG_PATH + " has " + validationErrors.size() + " problem(s):");
            for (String error : validationErrors) {
                System.err.println("[CONFIG]   - " + error);
            }
            System.exit(2);
            return;
        }

        // 1. Core Mathematical Models
        System.out.println("\n[MODULE 1] Core Mathematical Pricing Models");
        double demoSpot = config.getDouble("market_data.spot", 100.0);
        System.out.println(pricingSummary(
                demoSpot,
                config.getDouble("demo.strike", demoSpot * 1.05),
                config.getDouble("demo.expiry_years", 0.5),
                config.getDouble("market_data.risk_free_rate", 0.05),
                config.getDouble("volatility.default_volatility", 0.25),
                config.getDouble("market_data.dividend_yield", 0.0)));

        System.out.println("\n[MODULE 9] Launching Unified Quant Execution Engine...");
        AppCompositionRoot root = new AppCompositionRoot();

        List<OptionSnapshot> demoBacktest = List.of(
                new OptionSnapshot(Instant.parse("2024-01-02T09:30:00Z"), "SPY", LocalDate.of(2024, 1, 19), 510.0, OptionType.CALL, 510.0, 509.5, 510.5, 0.22, 1000, 2000),
                new OptionSnapshot(Instant.parse("2024-01-02T09:31:00Z"), "SPY", LocalDate.of(2024, 1, 19), 510.0, OptionType.CALL, 512.5, 511.8, 513.2, 0.25, 1200, 2100),
                new OptionSnapshot(Instant.parse("2024-01-02T09:32:00Z"), "SPY", LocalDate.of(2024, 1, 19), 510.0, OptionType.CALL, 515.0, 514.4, 515.6, 0.28, 1300, 2200),
                new OptionSnapshot(Instant.parse("2024-01-02T09:33:00Z"), "SPY", LocalDate.of(2024, 1, 19), 510.0, OptionType.CALL, 512.0, 511.7, 512.3, 0.30, 1250, 2150)
        );
        root.engine.runPortfolioBacktest(demoBacktest);

        root.harness.start();
        root.dashboard.start();
        root.surfaceService.start();
        root.valuationService.start();
        System.out.println("[SURFACE] Calibrating SSVI/SABR to the option chain in the background; the dashboard shows the status.");

        System.out.println("\n[SUCCESS] UNIFIED SYSTEM ONLINE AND PROCESSING REAL-TIME MMAP IPC STATE.");
        System.out.println("Options Trading Dashboard Live at: http://127.0.0.1:" + root.dashboard.getPort());
        System.out.println("[SERVER] Press Ctrl+C to terminate.");
        Runtime.getRuntime().addShutdownHook(new Thread(root::close, "shutdown"));
        while (true) {
            Thread.sleep(1000);
        }
    }

    /** The symbol the replay asks Alpaca for daily closes of; it is the symbol the engine trades by default. */
    private static final String REPLAY_SYMBOL = "SPY";

    /** The replay with real daily closes from Alpaca's bars; needs ALPACA_KEY_ID and ALPACA_SECRET in .env. */
    public static int runReplay(java.nio.file.Path targetDir) {
        return runReplay(targetDir, start -> {
            com.sbk.optionspricer.execution.AlpacaPaperClient client = com.sbk.optionspricer.execution.AlpacaPaperClient.fromEnv();
            return client.dailyBars(REPLAY_SYMBOL, start).stream()
                    .map(b -> new com.sbk.optionspricer.volatility.DailyCloseFeed.DailyClose(b.date(), b.close())).toList();
        });
    }

    /** The VIX history file the study reads, and where to get it. */
    static final String VIX_HISTORY_URL = "https://cdn.cboe.com/api/global/us_indices/daily_prices/VIX_History.csv";

    /** The premium study with SPY's closes from Alpaca (needs ALPACA_KEY_ID and ALPACA_SECRET in .env) and a VIX history file. */
    public static int runVrpStudy(java.nio.file.Path vixCsv) {
        return runVrpStudy(vixCsv, start -> {
            com.sbk.optionspricer.execution.AlpacaPaperClient client = com.sbk.optionspricer.execution.AlpacaPaperClient.fromEnv();
            return client.dailyBarsFrom(REPLAY_SYMBOL, start).stream()
                    .map(b -> new com.sbk.optionspricer.volatility.VrpStudy.Close(b.date(), b.close())).toList();
        });
    }

    /** Fewest observations worth summarising: about a year of days. */
    static final int VRP_STUDY_MIN_OBSERVATIONS = 250;

    /**
     * Sets implied volatility (the VIX) against the volatility SPY then had over the next 21 trading days, over as many years as
     * the two series share, and prints the premium, how often it was positive, how it varied with the level of the VIX, and what a
     * short variance position would have lost in its worst months. It is a study of the premium and not a backtest of a trade.
     * It stops, without a table, when either series is missing: it never invents data.
     */
    public static int runVrpStudy(java.nio.file.Path vixCsv,
                                  java.util.function.Function<java.time.LocalDate, java.util.List<com.sbk.optionspricer.volatility.VrpStudy.Close>> closesSource) {
        System.out.println("=========================================================================");
        System.out.println("        BLACKTHORN STERLING IMPLIED VS REALISED VOLATILITY STUDY         ");
        System.out.println("=========================================================================");
        java.util.Map<java.time.LocalDate, Double> vix;
        try {
            vix = com.sbk.optionspricer.volatility.VrpStudy.parseVixCsv(java.nio.file.Files.readAllLines(vixCsv));
        } catch (java.io.IOException | RuntimeException e) {
            System.err.println("[VRP] Cannot read the VIX history at " + vixCsv + " (" + e.getMessage() + ").");
            System.err.println("[VRP] Download it from " + VIX_HISTORY_URL + " and pass its path, or save it as data/historical/VIX_History.csv.");
            return 2;
        }
        if (vix.isEmpty()) {
            System.err.println("[VRP] No VIX closes found in " + vixCsv + ". Download " + VIX_HISTORY_URL);
            return 2;
        }
        java.util.List<com.sbk.optionspricer.volatility.VrpStudy.Close> closes;
        try {
            closes = closesSource.apply(java.time.LocalDate.of(2016, 1, 1));
        } catch (RuntimeException e) {
            System.err.println("[VRP] Cannot get SPY's daily closes: " + e.getMessage());
            System.err.println("[VRP] It never invents closes. Put ALPACA_KEY_ID and ALPACA_SECRET in .env and run it from the project folder.");
            return 2;
        }
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        closes = closes.stream().filter(c -> c.date().isBefore(today) && Double.isFinite(c.close()) && c.close() > 0.0).toList();
        java.util.List<com.sbk.optionspricer.volatility.VrpStudy.Observation> observations;
        try {
            observations = com.sbk.optionspricer.volatility.VrpStudy.observations(closes, vix, 21);
        } catch (IllegalArgumentException e) {
            observations = java.util.List.of();
        }
        if (observations.size() < VRP_STUDY_MIN_OBSERVATIONS) {
            System.err.println("[VRP] Only " + observations.size() + " days where the VIX and a following month of SPY closes both exist; at least "
                    + VRP_STUDY_MIN_OBSERVATIONS + " are needed.");
            return 2;
        }
        var r = com.sbk.optionspricer.volatility.VrpStudy.study(observations, 21);
        var f = java.util.Locale.ROOT;
        System.out.printf(f, "[VRP] %d days, %s to %s. Mean VIX %.1f; mean realised volatility over the next month %.1f.%n",
                r.observations(), r.first(), r.last(), 100 * r.meanImplied(), 100 * r.meanRealized());
        System.out.printf(f, "%nThe premium, VIX minus the volatility that followed, in vol points: mean %+.2f, median %+.2f; positive on %.0f%% of days.%n",
                r.meanPremiumPoints(), r.medianPremiumPoints(), 100 * r.shareWithPositivePremium());
        var q = r.premiumQuantilesPoints();
        System.out.printf(f, "  5%% %+.1f | 25%% %+.1f | 50%% %+.1f | 75%% %+.1f | 95%% %+.1f | worst %+.1f | best %+.1f%n",
                q.get(0), q.get(1), q.get(2), q.get(3), q.get(4), r.minPremiumPoints(), r.maxPremiumPoints());

        System.out.println("\nBy year:");
        System.out.println("| Year | Days | Mean premium | Positive | Worst 21-day payoff (any start day) |");
        System.out.println("| ---: | ---: | -----------: | -------: | ----------------------------------: |");
        for (var y : r.years()) {
            System.out.printf(f, "| %d | %d | %+.2f | %.0f%% | %+.1f |%n", y.year(), y.n(), y.meanPremiumPoints(), 100 * y.shareWithPositivePremium(), y.worstPayoffPoints());
        }
        System.out.println("\nBy the level of the VIX that day:");
        System.out.println("| Level | Days | Mean premium | Positive | Worst 21-day payoff |");
        System.out.println("| :---- | ---: | -----------: | -------: | ------------------: |");
        for (var b : r.buckets()) {
            System.out.printf(f, "| %s | %d | %s | %s | %s |%n", b.label(), b.n(),
                    b.n() == 0 ? "-" : String.format(f, "%+.2f", b.meanPremiumPoints()),
                    b.n() == 0 ? "-" : String.format(f, "%.0f%%", 100 * b.shareWithPositivePremium()),
                    b.n() == 0 ? "-" : String.format(f, "%+.1f", b.worstPayoffPoints()));
        }
        var m = r.monthly();
        System.out.printf(f, "%nNon-overlapping months (a short variance position sized at $1 a vol point, rolled every 21 trading days): %d months,%n", m.months());
        System.out.printf(f, "  mean payoff %+.2f vol points a month, standard deviation %.2f, negative in %.0f%% of months,%n",
                m.meanPayoffPoints(), m.stdPayoffPoints(), 100 * m.shareNegative());
        System.out.printf(f, "  worst month %+.1f (%s), largest fall from a peak %.1f vol points.%n", m.worstPayoffPoints(), m.worstDate(), m.maxDrawdownPoints());
        System.out.println("Worst five of those months (starting on every 21st day: the worst start day of any window is the \"worst 21-day payoff\" above, and is worse):");
        for (var o : m.worstFive()) {
            System.out.printf(f, "  %s: VIX %.1f, realised %.1f, payoff %+.1f%n", o.date(), 100 * o.impliedVol(), 100 * o.realizedVol(), o.payoffPoints());
        }
        System.out.println();
        System.out.println("[VRP] This is a study of the premium, not a backtest of any trade: no costs (spreads, fees), no margin, no options prices,");
        System.out.println("[VRP] and the VIX is the S&P 500's 30-day implied volatility, not SPY's. Daily windows overlap, so the daily rows are not");
        System.out.println("[VRP] independent samples. It covers one market and one stretch of history, and says nothing about the future.");
        return 0;
    }

    /** The forecast comparison with real daily closes from Alpaca's bars; needs ALPACA_KEY_ID and ALPACA_SECRET in .env. */
    public static int runForecastEval() {
        return runForecastEval(start -> {
            com.sbk.optionspricer.execution.AlpacaPaperClient client = com.sbk.optionspricer.execution.AlpacaPaperClient.fromEnv();
            return client.dailyBars(REPLAY_SYMBOL, start).stream()
                    .map(b -> new com.sbk.optionspricer.volatility.DailyCloseFeed.DailyClose(b.date(), b.close())).toList();
        });
    }

    /** Fewest daily closes worth comparing forecasts on: 66 to train on, a 21-day horizon, and some origins to score. */
    static final int FORECAST_EVAL_MIN_CLOSES = 120;

    /**
     * Compares realized-volatility forecasts (trailing, EWMA, HAR with its illustrative weights, HAR fitted on the days
     * known at each point) on real daily closes, walk-forward, and prints how well each predicted what volatility did
     * next. It stops without a table when it has no real closes: it never invents them.
     */
    public static int runForecastEval(java.util.function.Function<java.time.LocalDate, java.util.List<com.sbk.optionspricer.volatility.DailyCloseFeed.DailyClose>> closesSource) {
        System.out.println("=========================================================================");
        System.out.println("        BLACKTHORN STERLING REALIZED-VOL FORECAST COMPARISON             ");
        System.out.println("=========================================================================");
        java.util.List<com.sbk.optionspricer.volatility.DailyCloseFeed.DailyClose> bars;
        try {
            bars = closesSource.apply(java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(500));
        } catch (RuntimeException e) {
            System.err.println("[FORECAST] Cannot get real daily closes: " + e.getMessage());
            System.err.println("[FORECAST] It never invents closes. Put ALPACA_KEY_ID and ALPACA_SECRET in .env and run it from the project folder.");
            return 2;
        }
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        double[] closes = bars.stream()
                .filter(b -> b.date().isBefore(today) && Double.isFinite(b.close()) && b.close() > 0.0)
                .sorted(java.util.Comparator.comparing(com.sbk.optionspricer.volatility.DailyCloseFeed.DailyClose::date))
                .mapToDouble(com.sbk.optionspricer.volatility.DailyCloseFeed.DailyClose::close).toArray();
        if (closes.length < FORECAST_EVAL_MIN_CLOSES) {
            System.err.println("[FORECAST] Only " + closes.length + " completed daily closes; at least " + FORECAST_EVAL_MIN_CLOSES + " are needed.");
            return 2;
        }
        System.out.printf(java.util.Locale.ROOT, "[FORECAST] %s: %d completed daily closes. Each origin uses only the days before it.%n", REPLAY_SYMBOL, closes.length);
        for (int horizon : new int[]{5, 21}) {
            var result = com.sbk.optionspricer.volatility.ForecastEvaluator.evaluate(closes, horizon, 66);
            System.out.printf(java.util.Locale.ROOT, "%n%d days ahead: %d origins, mean realised vol %.1f%%, HAR fitted at %.0f%% of them%n",
                    horizon, result.evaluations(), 100 * result.meanRealized(), 100 * result.fittedShare());
            System.out.println("| Forecast      | RMSE (vol pts) | Bias (vol pts) | QLIKE  |");
            System.out.println("| :------------ | -------------: | -------------: | -----: |");
            for (var s : result.scores()) {
                System.out.printf(java.util.Locale.ROOT, "| %-13s | %14.2f | %+14.2f | %6.3f |%n", s.model(), 100 * s.rmse(), 100 * s.bias(), s.qlike());
            }
        }
        System.out.println();
        System.out.println("[FORECAST] Bias is forecast minus realised (negative = ran low). Lower RMSE and QLIKE are better.");
        System.out.println("[FORECAST] Origins are consecutive days, so their windows overlap: the scores are not independent, and a");
        System.out.println("[FORECAST] small gap between two models is within the noise. This is one asset over one stretch of history,");
        System.out.println("[FORECAST] and it measures prediction of realised volatility, not options prices or trading profit.");
        return 0;
    }

    /**
     * Replays the recorded chains against a realized-volatility forecast built from real daily closes. It reports
     * what the replay covers, and stops without a result when it has no real closes: a forecast made up for the
     * purpose would make every number below it meaningless.
     */
    public static int runReplay(java.nio.file.Path targetDir,
                                java.util.function.Function<java.time.LocalDate, java.util.List<com.sbk.optionspricer.volatility.DailyCloseFeed.DailyClose>> closesSource) {
        System.out.println("=========================================================================");
        System.out.println("        BLACKTHORN STERLING RECORDED CHAIN REPLAY & VRP BACKTEST         ");
        System.out.println("=========================================================================");
        System.out.println("[REPLAY] Scanning for recorded chain snapshots in: " + targetDir.toAbsolutePath());

        List<com.sbk.optionspricer.risk.VrpSignalBacktester.Snapshot> snapshots;
        try {
            snapshots = com.sbk.optionspricer.data.ChainSnapshotReader.loadSnapshots(targetDir);
        } catch (Exception e) {
            System.err.println("[REPLAY] Failed to load snapshots: " + e.getMessage());
            return 1;
        }
        if (snapshots.isEmpty()) {
            System.err.println("[REPLAY] No snapshot files (.csv.gz) found under " + targetDir);
            System.err.println("[REPLAY] Note: In Docker, recorded snapshots reside in volume 'engine-data' at /app/data/chain_snapshots.");
            return 1;
        }

        long moments = snapshots.stream().map(com.sbk.optionspricer.risk.VrpSignalBacktester.Snapshot::at).distinct().count();
        long days = snapshots.stream().map(s -> java.time.LocalDate.ofInstant(s.at(), java.time.ZoneOffset.UTC)).distinct().count();
        java.time.LocalDate firstDay = java.time.LocalDate.ofInstant(snapshots.get(0).at(), java.time.ZoneOffset.UTC);
        System.out.printf(java.util.Locale.ROOT, "[REPLAY] Loaded %d chains at %d moments over %d trading day%s (%s to %s)%n",
                snapshots.size(), moments, days, days == 1 ? "" : "s", snapshots.get(0).at(), snapshots.get(snapshots.size() - 1).at());

        List<com.sbk.optionspricer.volatility.DailyCloseFeed.DailyClose> closes;
        try {
            closes = closesSource.apply(firstDay.minusDays(400));
        } catch (RuntimeException e) {
            System.err.println("[REPLAY] Cannot get real daily closes, which the realized-volatility forecast needs: " + e.getMessage());
            System.err.println("[REPLAY] The replay never invents closes. Put ALPACA_KEY_ID and ALPACA_SECRET in .env and run it from the project folder.");
            return 2;
        }
        long usable = closes.stream().filter(c -> c.date().isBefore(firstDay)).count();
        if (usable < com.sbk.optionspricer.volatility.RealizedVolForecast.MIN_EWMA_RETURNS + 1) {
            System.err.println("[REPLAY] Only " + usable + " daily closes before " + firstDay + "; the forecast needs at least "
                    + (com.sbk.optionspricer.volatility.RealizedVolForecast.MIN_EWMA_RETURNS + 1) + ".");
            return 2;
        }
        System.out.println("[REPLAY] Realized vol: EWMA (lambda 0.94) of " + usable + " real daily closes; each day's forecast uses only earlier days.");

        var model = com.sbk.optionspricer.volatility.RealizedVolForecast.Model.EWMA;
        var rawResult = com.sbk.optionspricer.risk.VrpSignalBacktester.run(
                snapshots, closes, model, new com.sbk.optionspricer.risk.VrpSignalBacktester.ReplayConfig(0.01, 1, 0.65, false, 0, false));
        var demeanedResult = com.sbk.optionspricer.risk.VrpSignalBacktester.run(
                snapshots, closes, model, new com.sbk.optionspricer.risk.VrpSignalBacktester.ReplayConfig(0.01, 1, 0.65, true, 20, false));
        var hurdleResult = com.sbk.optionspricer.risk.VrpSignalBacktester.run(
                snapshots, closes, model, new com.sbk.optionspricer.risk.VrpSignalBacktester.ReplayConfig(0.01, 1, 0.65, true, 20, true));

        System.out.println();
        System.out.printf(java.util.Locale.ROOT, "Moments with a usable straddle and forecast: %d of %d. Mean ATM VRP over them: %+.2f vol points (implied minus forecast).%n%n",
                rawResult.evaluatedSteps(), rawResult.totalSnapshots(), 100 * rawResult.meanVrp());
        System.out.println("| Configuration (1 contract, fills at the touch, 0.65 a contract) | Trades | Win Rate | Total P&L   | Avg P&L/Trd | Max Drawdown |");
        System.out.println("| :-------------------------------------------------------------- | -----: | -------: | ----------: | ----------: | -----------: |");
        printReplayRow("1. Raw VRP (the level itself)", rawResult);
        printReplayRow("2. Demeaned VRP (vs 20-moment average)", demeanedResult);
        printReplayRow("3. Demeaned VRP + cost hurdle", hurdleResult);
        System.out.printf(java.util.Locale.ROOT, "%nThe cost hurdle (round-trip half-spread + fees over vega) rejected %d candidate entries in row 3.%n", hurdleResult.hurdlesRejected());

        System.out.println();
        if (days < 20) {
            System.out.printf(java.util.Locale.ROOT, "TOO FEW DAYS: %d trading day%s of snapshots is far too few to say whether any signal predicts anything. "
                    + "Read the figures as a check of the mechanics, not as evidence.%n", days, days == 1 ? "" : "s");
        }
        System.out.println("Not modelled: slippage beyond the touch, partial fills, assignment, a delta hedge; the forward and the rate are assumed.");
        return 0;
    }

    private static void printReplayRow(String label, com.sbk.optionspricer.risk.VrpSignalBacktester.VrpBacktestResult r) {
        System.out.printf(java.util.Locale.ROOT, "| %-63s | %6d | %7.1f%% | $%10.2f | $%10.2f | $%11.2f |\n",
                label, r.totalTrades(), 100 * r.winRate(), r.totalPnl(), r.averagePnlPerTrade(), r.maxDrawdown());
    }

    /**
     * Prints one UTC day of the strategy's decisions from the journal in {@code dataDir} ({@code decisions.csv}), with the
     * day's P&L from {@code pnl_history.csv}. With no day it reports the latest day the journal holds.
     */
    public static int runSessionReport(java.nio.file.Path dataDir, java.time.LocalDate day) {
        java.nio.file.Path journal = dataDir.resolve("decisions.csv");
        try {
            List<com.sbk.optionspricer.execution.DecisionJournal.Row> rows = com.sbk.optionspricer.execution.DecisionJournal.read(journal);
            if (rows.isEmpty()) {
                System.err.println("[REPORT] No decisions in " + journal.toAbsolutePath() + ": the decision journal is written by the build that added it, "
                        + "once the engine has made a decision. In Docker: docker compose exec options-engine java -jar /app/app.jar --session-report");
                return 1;
            }
            java.time.LocalDate reportDay = day != null ? day : rows.stream()
                    .map(r -> java.time.LocalDate.ofInstant(r.at(), java.time.ZoneOffset.UTC)).max(java.util.Comparator.naturalOrder()).orElseThrow();
            System.out.print(com.sbk.optionspricer.execution.SessionReport.build(rows,
                    com.sbk.optionspricer.execution.SessionReport.readPnl(dataDir.resolve("pnl_history.csv")), reportDay));
            return 0;
        } catch (java.io.IOException e) {
            System.err.println("[REPORT] Cannot read " + journal + ": " + e.getMessage());
            return 1;
        }
    }
    /** The paper multi-leg order test from the command line: a dry run unless {@code confirm} is true. */
    public static int runTestBuyStraddle(boolean confirm) {
        try {
            return runTestBuyStraddle(com.sbk.optionspricer.execution.AlpacaPaperClient.fromEnv(), confirm);
        } catch (Exception e) {
            System.err.println("[ERROR] Test buy-straddle failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Builds a one-contract buy straddle at the venue's touch and, only when {@code confirm} is true, sends it to the
     * Alpaca paper account as one multi-leg order. It bypasses the engine, so the engine's ledger never records the fill and
     * its reconciliation will halt trading until the position is flattened: a dry run is the default for that reason.
     */
    public static int runTestBuyStraddle(com.sbk.optionspricer.execution.AlpacaPaperClient client, boolean confirm) {
        System.out.println("=========================================================================");
        System.out.println("          ALPACA PAPER MULTI-LEG ORDER PATH TEST (BUY STRADDLE)          ");
        System.out.println("=========================================================================");
        try {
            var account = client.account();
            System.out.println("[ACCOUNT] Status: " + account.status() + " | Options Level: " + account.optionsTradingLevel()
                    + " | Buying Power: " + String.format(java.util.Locale.ROOT, "%.2f %s", account.buyingPower(), account.currency()));
            if (account.tradingBlocked()) {
                System.err.println("[ERROR] Account trading is blocked at venue.");
                return 1;
            }

            var spotQuote = client.latestQuote("SPY");
            double spot = (spotQuote.bid() + spotQuote.ask()) / 2.0;
            System.out.printf(java.util.Locale.ROOT, "[SPOT] SPY Touch: %.2f / %.2f (mid %.2f)\n", spotQuote.bid(), spotQuote.ask(), spot);

            double strike = Math.round(spot);
            LocalDate expiry = LocalDate.now().plusWeeks(4);
            while (expiry.getDayOfWeek() != java.time.DayOfWeek.FRIDAY) {
                expiry = expiry.plusDays(1);
            }
            String callSymbol = com.sbk.optionspricer.instruments.OccSymbol.format("SPY", expiry, OptionType.CALL, strike);
            String putSymbol = com.sbk.optionspricer.instruments.OccSymbol.format("SPY", expiry, OptionType.PUT, strike);
            System.out.println("[STRADDLE] Candidate legs: " + callSymbol + " and " + putSymbol);
            var callQuote = client.latestQuote(callSymbol);
            var putQuote = client.latestQuote(putSymbol);
            double limitPrice = Math.round((callQuote.ask() + putQuote.ask()) * 100.0) / 100.0;
            System.out.printf(java.util.Locale.ROOT, "[TOUCH] Call Ask: %.2f | Put Ask: %.2f | Net Debit Limit: %.2f\n",
                    callQuote.ask(), putQuote.ask(), limitPrice);

            if (!confirm) {
                System.out.println("[DRY RUN] No order was sent. This would send a 1-contract multi-leg buy straddle to the Alpaca PAPER account.");
                System.out.println("[DRY RUN] It bypasses the engine, so the engine's ledger will not record the fill and its reconciliation will halt");
                System.out.println("[DRY RUN] trading until the position is flattened on Alpaca. Stop the engine first (docker compose stop), then add --confirm.");
                return 0;
            }

            List<com.sbk.optionspricer.execution.AlpacaPaperClient.OptionLeg> legs = List.of(
                    new com.sbk.optionspricer.execution.AlpacaPaperClient.OptionLeg(callSymbol, 1, true),
                    new com.sbk.optionspricer.execution.AlpacaPaperClient.OptionLeg(putSymbol, 1, true));
            String clientOrderId = "test_straddle_" + System.currentTimeMillis();
            System.out.println("[SUBMIT] Sending 1-contract multi-leg limit order to Alpaca Paper (ID: " + clientOrderId + ")...");
            var orderState = client.submitMultiLegOrder(legs, 1, limitPrice, clientOrderId);
            System.out.println("[RESPONSE] Alpaca Order ID: " + orderState.id() + " | Status: " + orderState.status());
            System.out.println("[NOTE] The engine's ledger does not know about this order. If it fills, flatten the position on Alpaca's paper dashboard,");
            System.out.println("[NOTE] or the engine's reconciliation will halt trading at its next check.");
            return 0;
        } catch (Exception e) {
            System.err.println("[ERROR] Test buy-straddle failed: " + e.getMessage());
            return 1;
        }
    }
}
