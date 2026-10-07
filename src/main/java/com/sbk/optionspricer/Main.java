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
 * Unified Main Entry Point for the AURA-OPT Options Pricing Engine.
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
        System.out.println("=========================================================================");
        System.out.println("      AURA-OPT INSTITUTIONAL OPTIONS PRICING & EXECUTION PLATFORM        ");
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
        System.out.println("[SURFACE] Calibrating SSVI/SABR to the option chain in the background; the dashboard shows the status.");

        System.out.println("\n[SUCCESS] UNIFIED SYSTEM ONLINE AND PROCESSING REAL-TIME MMAP IPC STATE.");
        System.out.println("Options Trading Dashboard Live at: http://127.0.0.1:" + root.dashboard.getPort());
        System.out.println("[SERVER] Press Ctrl+C to terminate.");
        Runtime.getRuntime().addShutdownHook(new Thread(root::close, "shutdown"));
        while (true) {
            Thread.sleep(1000);
        }
    }
}
