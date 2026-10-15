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
