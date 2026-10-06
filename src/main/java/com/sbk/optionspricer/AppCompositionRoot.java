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

    public AppCompositionRoot() throws Exception {
        this.config = new ConfigManager();
        this.publisher = new MmapStatePublisher();
        this.engine = UnifiedQuantEngine.fromConfig(config, publisher);
        
        String symbol = config.getString("execution.symbol", "SPY");
        double slippageBps = config.getDouble("execution.slippage_bps", 25.0);
        double maxNotional = config.getDouble("risk.max_notional", 1000000.0);
        double maxDelta = config.getDouble("risk.max_delta", 5000.0);
        double maxGamma = config.getDouble("risk.max_gamma", 1000.0);
        double maxVega = config.getDouble("risk.max_vega", 10000.0);
        double maxPositionAbs = config.getDouble("risk.max_position", 10000.0);
        double baseQuantity = config.getDouble("strategy.base_quantity", 10.0);
        double triggerPct = config.getDouble("strategy.trigger_pct", 0.001);

        this.positionTracker = new PositionTracker();
        this.spotProvider = new LiveSpotProvider();
        this.marketAdapter = new LiveMarketSnapshotAdapter(spotProvider);
        this.preTradeFilter = new PreTradeRiskFilter((int) maxPositionAbs, maxNotional, 100);
        this.riskAdmission = new PortfolioRiskAdmission(maxNotional, maxDelta, maxGamma, maxVega, maxPositionAbs);
        this.executionTransport = new PaperTradingExecutionAdapter(positionTracker, slippageBps);
        boolean allowSimulated = Boolean.parseBoolean(
                com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("ALLOW_SIMULATED_DATA", "false"));
        OrderManager.MarketDataPolicy dataPolicy = allowSimulated
                ? OrderManager.MarketDataPolicy.allowSimulated()
                : OrderManager.MarketDataPolicy.strict();
        com.sbk.optionspricer.execution.TradingHalt tradingHalt = new com.sbk.optionspricer.execution.TradingHalt();
        tradingHalt.haltOnCriticalAlerts(engine.getGreekAlertManager());
        this.orderManager = new OrderManager(preTradeFilter, executionTransport, positionTracker, dataPolicy, tradingHalt);
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
    }
}
