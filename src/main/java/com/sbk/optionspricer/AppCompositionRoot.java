package com.sbk.optionspricer;

import com.sbk.optionspricer.config.ConfigManager;
import com.sbk.optionspricer.core.MmapStatePublisher;
import com.sbk.optionspricer.core.QuantSimulationHarness;
import com.sbk.optionspricer.core.UnifiedQuantEngine;
import com.sbk.optionspricer.execution.*;
import com.sbk.optionspricer.market.LiveMarketSnapshotAdapter;
import com.sbk.optionspricer.web.LiveSpotProvider;
import com.sbk.optionspricer.web.MmapStateReader;
import com.sbk.optionspricer.web.OptionsDashboardServer;

import java.io.IOException;

public class AppCompositionRoot {

    public final ConfigManager config;
    public final MmapStatePublisher publisher;
    public final UnifiedQuantEngine engine;
    public final PositionTracker positionTracker;
    public final LiveSpotProvider spotProvider;
    public final LiveMarketSnapshotAdapter marketAdapter;
    public final PreTradeRiskFilter preTradeFilter;
    public final PortfolioRiskAdmission riskAdmission;
    public final PaperTradingExecutionAdapter executionTransport;
    public final OrderManager orderManager;
    public final StrategyExecutionLoop strategyLoop;
    public final QuantSimulationHarness harness;
    public final OptionsDashboardServer dashboard;
    public final com.sbk.optionspricer.core.VolatilitySurfaceService surfaceService;

    public AppCompositionRoot() throws Exception {
        this.config = new ConfigManager();
        this.publisher = new MmapStatePublisher();
        this.engine = UnifiedQuantEngine.fromConfig(config, publisher);

        String symbol = config.getString("execution.symbol", "SPY");
        double slippageBps = config.getDouble("execution.slippage_bps", 25.0);
        double maxNotional = config.getDouble("risk.max_notional", ConfigManager.DEFAULT_MAX_NOTIONAL);
        double maxDelta = config.getDouble("risk.max_delta", ConfigManager.DEFAULT_MAX_DELTA);
        double maxGamma = config.getDouble("risk.max_gamma", ConfigManager.DEFAULT_MAX_GAMMA);
        double maxVega = config.getDouble("risk.max_vega", ConfigManager.DEFAULT_MAX_VEGA);
        double maxPositionAbs = config.getDouble("risk.max_position", ConfigManager.DEFAULT_MAX_POSITION);
        double baseQuantity = config.getDouble("strategy.base_quantity", 10.0);
        double triggerPct = config.getDouble("strategy.trigger_pct", 0.001);
        int contractMultiplier = (int) config.getDouble("execution.contract_multiplier", 1.0);

        this.positionTracker = new PositionTracker();
        engine.setExposureSource(positionTracker::snapshotExposure);
        this.spotProvider = new LiveSpotProvider();
        this.marketAdapter = new LiveMarketSnapshotAdapter(spotProvider);
        double maxConcentration = config.getDouble("risk.max_concentration", maxNotional);
        this.preTradeFilter = new PreTradeRiskFilter((int) maxPositionAbs, maxNotional, 100, java.util.Map.of(),
                new com.sbk.optionspricer.risk.ConcentrationLimitManager(
                        java.util.Map.of(symbol.trim().toUpperCase(java.util.Locale.ROOT), maxConcentration)),
                null);
        this.riskAdmission = new PortfolioRiskAdmission(maxNotional, maxDelta, maxGamma, maxVega, maxPositionAbs);
        this.executionTransport = new PaperTradingExecutionAdapter(positionTracker, slippageBps);
        boolean allowSimulated = Boolean.parseBoolean(
                com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("ALLOW_SIMULATED_DATA", "false"));
        OrderManager.MarketDataPolicy dataPolicy = allowSimulated
                ? OrderManager.MarketDataPolicy.allowSimulated()
                : OrderManager.MarketDataPolicy.strict();
        com.sbk.optionspricer.execution.TradingHalt tradingHalt = new com.sbk.optionspricer.execution.TradingHalt();
        tradingHalt.haltOnCriticalAlerts(engine.getGreekAlertManager());
        this.orderManager = new OrderManager(preTradeFilter, executionTransport, positionTracker, dataPolicy, tradingHalt, riskAdmission, contractMultiplier);
        this.strategyLoop = new StrategyExecutionLoop(symbol, orderManager, riskAdmission, positionTracker, baseQuantity, triggerPct, marketAdapter);

        this.harness = new QuantSimulationHarness(engine, spotProvider, positionTracker, orderManager, riskAdmission, strategyLoop);

        String apiSecret = OptionsDashboardServer.requireEnvironmentVariable("API_SECRET");
        String operatorPassword = OptionsDashboardServer.requireEnvironmentVariable("OPERATOR_PASSWORD");
        if (operatorPassword.length() < 12) {
            throw new IllegalStateException("FATAL: OPERATOR_PASSWORD must be at least 12 characters long.");
        }
        String bindAddress = com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("BIND_ADDRESS", "127.0.0.1");
        String portEnv = System.getProperty("PORT", com.sbk.optionspricer.config.EnvironmentConfigLoader.get("PORT"));
        int port = portEnv == null ? 8080 : Integer.parseInt(portEnv);
        String allowedOrigins = com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("ALLOWED_ORIGIN", "http://127.0.0.1:" + port + ",http://localhost:" + port);

        MmapStateReader mmapReader = new MmapStateReader();
        this.dashboard = new OptionsDashboardServer(apiSecret, operatorPassword, allowedOrigins, bindAddress, port, port + 1, mmapReader, "web", orderManager, positionTracker, marketAdapter);

        // Chains are fetched and the surface fitted off the startup path; the dashboard labels the result with its source.
        double riskFreeRate = config.getDouble("market_data.risk_free_rate", 0.05);
        double dividendYield = config.getDouble("market_data.dividend_yield", 0.0);
        long refreshSeconds = Math.max(30L, (long) config.getDouble("market_data.refresh_interval_seconds", 900.0));
        this.surfaceService = new com.sbk.optionspricer.core.VolatilitySurfaceService(engine.getOptionChainProvider(), symbol,
                riskFreeRate, dividendYield, java.time.Duration.ofSeconds(refreshSeconds), java.time.Clock.systemUTC(), engine::recordChainSnapshots);
        dashboard.setSurfaceSource(surfaceService);
        dashboard.setOperatorControls(new com.sbk.optionspricer.core.OperatorConsole(tradingHalt, harness, strategyLoop));
    }
}
