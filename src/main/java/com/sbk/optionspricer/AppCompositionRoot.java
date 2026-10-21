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
    public final ExchangeTransport executionTransport;
    /** Null unless the Alpaca transport is selected. */
    public final BookReconciler bookReconciler;
    public final OrderManager orderManager;
    public final StrategyExecutionLoop strategyLoop;
    public final QuantSimulationHarness harness;
    public final OptionsDashboardServer dashboard;
    public final com.sbk.optionspricer.core.VolatilitySurfaceService surfaceService;
    public final com.sbk.optionspricer.core.PortfolioValuationService valuationService;
    public final com.sbk.optionspricer.execution.VolSpreadStrategy volSpreadStrategy;
    public final com.sbk.optionspricer.data.CompressedChainSnapshotRecorder chainRecorder;
    public final com.sbk.optionspricer.alert.PushNotificationService pushNotifier;
    public final com.sbk.optionspricer.core.HeartbeatMonitor heartbeatMonitor;

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

        // The book is rebuilt from the append-only fill ledger, so positions, cost and realised P&L survive a restart.
        java.nio.file.Path dataDir = java.nio.file.Path.of(com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("DATA_DIR", "data"));
        com.sbk.optionspricer.risk.FillLedger fillLedger = new com.sbk.optionspricer.risk.FillLedger(dataDir.resolve("fills.csv"));
        this.positionTracker = PositionTracker.restore(fillLedger);
        System.out.println("[LEDGER] " + fillLedger.path() + ": " + positionTracker.replayedFills() + " fills replayed, "
                + positionTracker.getPositions().values().stream().filter(p -> p.getQuantity() != 0).count() + " open positions");
        engine.setExposureSource(positionTracker::snapshotExposure);
        com.sbk.optionspricer.market.StalenessPolicy.configure(
                com.sbk.optionspricer.market.StalenessPolicy.fromEnvironment(com.sbk.optionspricer.config.EnvironmentConfigLoader::get));
        this.spotProvider = new LiveSpotProvider();
        this.marketAdapter = new LiveMarketSnapshotAdapter(spotProvider);
        double maxConcentration = config.getDouble("risk.max_concentration", maxNotional);
        this.preTradeFilter = preTradeFilter(config, symbol, maxPositionAbs, maxNotional, maxConcentration);
        this.riskAdmission = new PortfolioRiskAdmission(maxNotional, maxDelta, maxGamma, maxVega, maxPositionAbs);
        com.sbk.optionspricer.execution.TradingHalt tradingHalt = new com.sbk.optionspricer.execution.TradingHalt();
        this.pushNotifier = new com.sbk.optionspricer.alert.PushNotificationService();
        pushNotifier.monitorTradingHalt(tradingHalt);

        // Heartbeat monitor to detect crashes, power loss, or laptop standby
        this.heartbeatMonitor = new com.sbk.optionspricer.core.HeartbeatMonitor();
        // Where orders go: the in-process simulator, or an Alpaca paper account (the client can only address the
        // paper endpoint). With Alpaca, the book is reconciled with the account at start and every minute.
        String transportName = config.getString("execution.transport", "paper");
        java.util.function.Supplier<TransportStatus> transportStatus;
        AlpacaPaperClient alpacaClient = null;
        if ("alpaca".equals(transportName)) {
            alpacaClient = AlpacaPaperClient.fromEnv();
            AlpacaPaperClient alpaca = alpacaClient;
            long fillWaitSeconds = Math.max(1L, (long) config.getDouble("execution.fill_wait_seconds", 10.0));
            AlpacaPaperTransport alpacaTransport = new AlpacaPaperTransport(alpaca, java.time.Duration.ofSeconds(fillWaitSeconds));
            double limitCushionBps = config.getDouble("execution.limit_cushion_bps", 0.0);
            alpacaTransport.setLimitCushionBps(limitCushionBps);
            if (limitCushionBps > 0.0) {
                System.out.printf(java.util.Locale.ROOT, "[EXECUTION] Limits are set %.0f bps past the touch (a buy higher, a sell lower); a marketable limit still fills at the market%n", limitCushionBps);
            }
            this.executionTransport = alpacaTransport;
            this.bookReconciler = new BookReconciler(alpaca, positionTracker, tradingHalt, java.time.Duration.ofMinutes(1), java.time.Clock.systemUTC());
            transportStatus = () -> alpacaTransport.status(bookReconciler.lastResult());
            try {
                AlpacaPaperClient.Account account = alpaca.account();
                System.out.println("[TRANSPORT] alpaca paper account " + account.status() + ", options level " + account.optionsTradingLevel()
                        + ", buying power " + String.format(java.util.Locale.ROOT, "%.0f %s", account.buyingPower(), account.currency())
                        + (account.tradingBlocked() ? ", TRADING BLOCKED" : ""));
            } catch (RuntimeException e) {
                System.err.println("[TRANSPORT] alpaca paper account not reachable at start: " + e.getMessage() + "; orders will be rejected until it is");
            }
        } else {
            this.executionTransport = new PaperTradingExecutionAdapter(positionTracker, slippageBps);
            this.bookReconciler = null;
            TransportStatus paper = TransportStatus.paper(slippageBps);
            transportStatus = () -> paper;
            System.out.println("[TRANSPORT] paper: orders fill in the in-process simulator");
        }
        boolean allowSimulated = Boolean.parseBoolean(
                com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("ALLOW_SIMULATED_DATA", "false"));
        OrderManager.MarketDataPolicy dataPolicy = allowSimulated
                ? OrderManager.MarketDataPolicy.allowSimulated()
                : OrderManager.MarketDataPolicy.strict();
        tradingHalt.haltOnCriticalAlerts(engine.getGreekAlertManager());
        this.orderManager = new OrderManager(preTradeFilter, executionTransport, positionTracker, dataPolicy, tradingHalt, riskAdmission, contractMultiplier);
        this.strategyLoop = new StrategyExecutionLoop(symbol, orderManager, riskAdmission, positionTracker, baseQuantity, triggerPct, marketAdapter);

        this.harness = new QuantSimulationHarness(engine, spotProvider, positionTracker, orderManager, riskAdmission, strategyLoop);
        harness.setSymbol(symbol);

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

        dashboard.setSymbol(symbol);
        // Chains are fetched and the surface fitted off the startup path; the dashboard labels the result with its source.
        double riskFreeRate = config.getDouble("market_data.risk_free_rate", 0.05);
        double dividendYield = config.getDouble("market_data.dividend_yield", 0.0);
        long refreshSeconds = Math.max(30L, (long) config.getDouble("market_data.refresh_interval_seconds", 900.0));
        // Quotes are reloaded far more often than the surface is refitted: the strategy judges a quote STALE after 120 s.
        long quoteSeconds = Math.max(10L, (long) config.getDouble("market_data.quote_refresh_seconds", 60.0));
        this.surfaceService = new com.sbk.optionspricer.core.VolatilitySurfaceService(engine.getOptionChainProvider(), symbol,
                riskFreeRate, dividendYield, java.time.Duration.ofSeconds(refreshSeconds), java.time.Duration.ofSeconds(quoteSeconds),
                java.time.Clock.systemUTC(), engine::recordChainSnapshots);
        dashboard.setSurfaceSource(surfaceService);
        // Every calibration is recorded so ATM vol and skew can be charted over the day, across restarts.
        com.sbk.optionspricer.volatility.SurfaceHistory surfaceHistory = new com.sbk.optionspricer.volatility.SurfaceHistory(dataDir.resolve("surface_history.csv"));
        surfaceService.setSnapshotListener(surfaceHistory);
        dashboard.setSurfaceHistory(surfaceHistory);
        System.out.println("[SURFACE HISTORY] " + surfaceHistory.path() + ": " + surfaceHistory.size() + " model calibrations on file");

        // Every chain the service loads is kept (gzip, one file per change in the feed) so a strategy can later be
        // tested on what the market really showed. Off with market_data.record_chain_snapshots: false.
        if (config.getBoolean("market_data.record_chain_snapshots", true)) {
            java.nio.file.Path snapshotsDir = dataDir.resolve("chain_snapshots");
            this.chainRecorder = new com.sbk.optionspricer.data.CompressedChainSnapshotRecorder(snapshotsDir, java.time.Clock.systemUTC());
            surfaceService.setQuoteRefreshListener(chains -> chainRecorder.recordSourced(chains, java.time.Instant.now()));
            System.out.println("[CHAIN RECORDER] keeping a snapshot of the option chains whenever the feed changes, in " + snapshotsDir);
            dashboard.setRecorderStatus(() -> {
                java.util.Map<String, Object> status = new java.util.LinkedHashMap<>();
                status.put("status", "ON");
                status.put("filesWritten", chainRecorder.totalFilesWritten());
                status.put("quotesRecorded", chainRecorder.totalQuotesRecorded());
                status.put("skippedUnchanged", chainRecorder.skippedUnchanged());
                status.put("lastWrittenAt", chainRecorder.lastWrittenAt().map(java.time.Instant::toEpochMilli).orElse(null));
                return status;
            });
        } else {
            this.chainRecorder = null;
            System.out.println("[CHAIN RECORDER] off (market_data.record_chain_snapshots is false)");
        }
        com.sbk.optionspricer.core.OperatorConsole console = new com.sbk.optionspricer.core.OperatorConsole(tradingHalt, harness, strategyLoop);
        dashboard.setOperatorControls(console);
        dashboard.setControlAudit(new com.sbk.optionspricer.web.ControlAuditLog(dataDir.resolve("control_audit.csv"), java.time.Clock.systemUTC()));
        dashboard.setFeedStatusSource(spotProvider::lastKnownFeedStatus);
        dashboard.setTransportStatus(transportStatus);

        // Marks the book every few seconds: option Greeks from the fitted surface, P&L against average cost.
        this.valuationService = new com.sbk.optionspricer.core.PortfolioValuationService(positionTracker, surfaceService, surfaceService,
                () -> marketAdapter.getSnapshot(symbol), riskFreeRate, dividendYield, java.time.Duration.ofSeconds(5), java.time.Clock.systemUTC());
        dashboard.setValuationSource(valuationService);
        // A fill is valued at once, so a new option position never sits unpriced until the next scheduled mark.
        orderManager.setFillListener(valuationService::revalue);
        // The valuation is sampled once a minute (and whenever realised P&L changes) for the day's P&L and drawdown.
        com.sbk.optionspricer.execution.PnlHistory pnlHistory = new com.sbk.optionspricer.execution.PnlHistory(dataDir.resolve("pnl_history.csv"), java.time.Duration.ofSeconds(60));
        valuationService.setValuationListener(pnlHistory);
        dashboard.setPnlHistory(pnlHistory);
        System.out.println("[PNL HISTORY] " + pnlHistory.path() + ": " + pnlHistory.size() + " samples on file");

        // The options strategy: front-month ATM straddle against the fitted surface, hedged with shares.
        String strategyMode = config.getString("strategy.mode", "vol_spread");
        boolean useMultiLeg = config.getBoolean("strategy.use_multi_leg", false);
        String allowedSideStr = config.getString("strategy.side", "long_only");
        com.sbk.optionspricer.execution.VolSpreadStrategy.AllowedSide allowedSide =
                com.sbk.optionspricer.execution.VolSpreadStrategy.AllowedSide.parse(allowedSideStr);
        double rmseMultiplier = config.getDouble("strategy.rmse_multiplier", 0.0);
        com.sbk.optionspricer.execution.VolSpreadStrategy.Params strategyParams = new com.sbk.optionspricer.execution.VolSpreadStrategy.Params(
                config.getDouble("strategy.vol_edge", 0.01), config.getDouble("strategy.hedge_band", 50.0),
                (int) config.getDouble("strategy.max_days_to_expiry", 7.0), (int) config.getDouble("strategy.min_days_to_expiry", 14.0),
                (int) config.getDouble("strategy.contracts", 1.0), config.getString("strategy.reference_model", "SSVI"),
                config.getDouble("strategy.fee_per_contract", 0.65), useMultiLeg, allowedSide, rmseMultiplier);
        if (rmseMultiplier > 0.0) {
            System.out.printf(java.util.Locale.ROOT, "[STRATEGY] Dynamic surface RMSE hurdle is ON: requiring %.1fx surface RMSE before opening%n", rmseMultiplier);
        }
        System.out.println("[STRATEGY] side=" + allowedSide + (allowedSide == com.sbk.optionspricer.execution.VolSpreadStrategy.AllowedSide.LONG_ONLY
                ? " (only buys straddles; set strategy.side: both to let it sell them)" : ""));
        if (useMultiLeg) {
            System.out.println("[STRATEGY] multi-leg orders are ON (experimental): a buy straddle goes as one order; "
                    + "allowedSide=" + allowedSide);
        }
        com.sbk.optionspricer.volatility.FairVolProvider fairVol = fairVolShadow(config, symbol);
        com.sbk.optionspricer.execution.VolSpreadStrategy.LegQuoteSource legQuoteSource = null;
        if (alpacaClient != null) {
            final AlpacaPaperClient ac = alpacaClient;
            legQuoteSource = occSymbol -> {
                try {
                    AlpacaPaperClient.Quote q = ac.latestQuote(occSymbol);
                    return AlpacaPaperClient.toOptionSnapshot(occSymbol, q, java.time.Clock.systemUTC());
                } catch (RuntimeException e) {
                    return java.util.Optional.empty();
                }
            };
            System.out.println("[STRATEGY] Leg quote source: Alpaca's own latest option quote (indicative feed, labelled DELAYED) for the straddle legs; Cboe still feeds the surface");
        }
        this.volSpreadStrategy = new com.sbk.optionspricer.execution.VolSpreadStrategy(orderManager, surfaceService, surfaceService, fairVol,
                valuationService, () -> marketAdapter.getSnapshot(symbol), positionTracker, symbol, riskFreeRate, dividendYield, strategyParams,
                java.time.Clock.systemUTC(), legQuoteSource);
        // every decision is also appended to data/decisions.csv: the console log does not survive a rebuild
        com.sbk.optionspricer.execution.DecisionJournal decisionJournal = new com.sbk.optionspricer.execution.DecisionJournal(dataDir.resolve("decisions.csv"));
        // phone messages, through the same ntfy topic or webhook as the halt alert; only when one is configured
        com.sbk.optionspricer.alert.TradeNotifier tradeNotifier = pushNotifier.hasRemoteChannel()
                ? new com.sbk.optionspricer.alert.TradeNotifier(pushNotifier::sendNotification) : null;
        this.tradeNotifier = tradeNotifier;
        volSpreadStrategy.setDecisionListener(decision -> {
            decisionJournal.record(decision);
            if (tradeNotifier != null) {
                tradeNotifier.accept(decision);
            }
        });
        System.out.println("[JOURNAL] strategy decisions are appended to " + decisionJournal.path());
        harness.setStrategyMode(strategyMode);
        harness.setOptionStrategy(volSpreadStrategy::step, java.time.Duration.ofSeconds((long) config.getDouble("strategy.option_interval_seconds", 30.0)));
        console.setSupervisedStraddle(volSpreadStrategy::supervisedBuyStraddle);
        console.describeStrategy(harness::getStrategyMode,
                () -> volSpreadStrategy.lastDecision().map(com.sbk.optionspricer.execution.VolSpreadStrategy.Decision::summary).orElse(null));

        if (bookReconciler != null) {
            bookReconciler.serializeWith(orderManager); // never read the books between an order's fill at Alpaca and its booking
            // The first reconciliation runs before any order can be placed; a mismatch halts trading from the start.
            BookReconciler.Result first = bookReconciler.reconcile();
            System.out.println("[RECONCILE] " + first.status() + ": " + first.message());
            bookReconciler.start();
        }

        if (pushNotifier.hasRemoteChannel()) {
            java.util.function.Supplier<String> status = () -> phoneStatus(tradingHalt, pnlHistory);
            this.statusDigest = new com.sbk.optionspricer.alert.StatusDigest(pushNotifier::sendNotification, status, () -> {
                try {
                    return com.sbk.optionspricer.execution.SessionReport.forDay(dataDir, java.time.LocalDate.now(java.time.ZoneOffset.UTC));
                } catch (java.io.IOException e) {
                    return status.get();
                }
            }, java.time.Clock.systemUTC(), new com.sbk.optionspricer.market.MarketCalendar());
            statusDigest.sendStartup(status.get() + "\nLast run: " + heartbeatMonitor.describePreviousRun());
            statusDigest.start();
            System.out.println("[ALERT] Status messages to the phone: on start, after the US open and after the close on weekdays, and on every trade");
        }

        // Start heartbeat monitor after everything else is initialized
        heartbeatMonitor.start();
    }

    /** One short block for the phone: whether trading is on, whether the books agree with Alpaca, the positions, the last decision and the P&L. */
    private String phoneStatus(com.sbk.optionspricer.execution.TradingHalt halt, com.sbk.optionspricer.execution.PnlHistory pnl) {
        StringBuilder sb = new StringBuilder();
        sb.append(halt.isHalted()
                ? "TRADING HALTED: " + halt.reason().map(com.sbk.optionspricer.execution.TradingHalt.Reason::message).orElse("no reason given")
                : "Trading active");
        if (bookReconciler != null && bookReconciler.lastResult() != null) {
            sb.append(" | books vs Alpaca: ").append(bookReconciler.lastResult().status());
        }
        java.util.List<String> held = new java.util.ArrayList<>();
        positionTracker.getPositions().forEach((symbol, position) -> {
            if (position.getQuantity() != 0) {
                held.add(symbol + " " + position.getQuantity());
            }
        });
        sb.append("\nPositions: ").append(held.isEmpty() ? "none" : String.join(", ", held));
        volSpreadStrategy.lastDecision().ifPresent(d -> sb.append("\nLast decision: ").append(d.summary()));
        pnl.summary(java.time.Instant.now()).ifPresent(s -> sb.append(String.format(java.util.Locale.ROOT,
                "%nP&L: total %+.2f (realised %+.2f, unrealised %+.2f), today %+.2f", s.total(), s.realized(), s.unrealized(), s.dayPnl())));
        return sb.toString();
    }

    /**
     * The realized-vol forecast the strategy reports next to each hold ({@code strategy.fair_vol_model}), or null
     * for NONE. It never trades: the reference stays the fitted surface. Its daily closes come from Alpaca's bars with
     * the keys in .env; without them, or when the load fails, the forecast stays empty and the readout is simply absent.
     */
    private com.sbk.optionspricer.volatility.FairVolProvider fairVolShadow(ConfigManager config, String symbol) {
        String name = config.getString("strategy.fair_vol_model", "NONE").trim().toUpperCase(java.util.Locale.ROOT);
        if ("NONE".equals(name)) {
            return null;
        }
        com.sbk.optionspricer.volatility.RealizedVolForecast forecast = new com.sbk.optionspricer.volatility.RealizedVolForecast(
                "HAR".equals(name) ? com.sbk.optionspricer.volatility.RealizedVolForecast.Model.HAR
                        : com.sbk.optionspricer.volatility.RealizedVolForecast.Model.EWMA);
        try {
            AlpacaPaperClient client = AlpacaPaperClient.fromEnv();
            this.dailyCloseFeed = new com.sbk.optionspricer.volatility.DailyCloseFeed(forecast,
                    start -> client.dailyBars(symbol, start).stream()
                            .map(b -> new com.sbk.optionspricer.volatility.DailyCloseFeed.DailyClose(b.date(), b.close())).toList(),
                    java.time.Clock.systemUTC(), java.time.Duration.ofHours(6));
            dailyCloseFeed.start();
            System.out.println("[FAIR VOL] " + name + " realized-vol forecast reported beside each hold (shadow only, never trades)");
        } catch (RuntimeException e) {
            System.err.println("[FAIR VOL] needs ALPACA_KEY_ID and ALPACA_SECRET for daily closes: " + e.getMessage() + "; no forecast will be shown");
        }
        return forecast;
    }

    /**
     * The pre-trade filter the application runs: size, notional, per-underlying concentration, and the liquidity
     * gate (spread in basis points and volume, {@code risk.max_spread_bps} and {@code risk.min_volume}). It was
     * built without the liquidity gate until 2026-10-10, which the docs described as active. A field the data
     * source did not supply is not judged, so a spot feed without a book or a volume passes.
     */
    static PreTradeRiskFilter preTradeFilter(ConfigManager config, String symbol, double maxPositionAbs, double maxNotional, double maxConcentration) {
        com.sbk.optionspricer.risk.LiquidityRiskMonitor liquidity = new com.sbk.optionspricer.risk.LiquidityRiskMonitor(
                config.getDouble("risk.max_spread_bps", ConfigManager.DEFAULT_MAX_SPREAD_BPS),
                (long) config.getDouble("risk.min_volume", ConfigManager.DEFAULT_MIN_VOLUME));
        return new PreTradeRiskFilter((int) maxPositionAbs, maxNotional, 100, java.util.Map.of(),
                new com.sbk.optionspricer.risk.ConcentrationLimitManager(
                        java.util.Map.of(symbol.trim().toUpperCase(java.util.Locale.ROOT), maxConcentration)),
                liquidity);
    }

    /**
     * Orderly shutdown: stop serving first, then the background calibration, then the engine (which closes the
     * mmap state). Each step is guarded so one failure does not skip the others. Idempotent.
     */
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        System.out.println("[SHUTDOWN] stopping dashboard, surface calibration and engine");
        step("dashboard", dashboard::stop);
        if (bookReconciler != null) {
            step("reconciliation", bookReconciler::close);
        }
        step("valuation", valuationService::close);
        step("surface calibration", surfaceService::close);
        if (dailyCloseFeed != null) {
            step("daily closes", dailyCloseFeed::close);
        }
        if (chainRecorder != null) {
            step("chain recorder", chainRecorder::close);
        }
        if (statusDigest != null) {
            step("status messages", statusDigest::close);
        }
        if (tradeNotifier != null) {
            step("trade notices", tradeNotifier::close);
        }
        if (pushNotifier != null) {
            step("push notifications", pushNotifier::close);
        }
        if (heartbeatMonitor != null) {
            step("heartbeat monitor", heartbeatMonitor::close);
        }
        step("engine", harness::stop);
        System.out.println("[SHUTDOWN] done");
    }

    private boolean closed;
    private com.sbk.optionspricer.volatility.DailyCloseFeed dailyCloseFeed;
    private com.sbk.optionspricer.alert.StatusDigest statusDigest;
    private com.sbk.optionspricer.alert.TradeNotifier tradeNotifier;

    private static void step(String name, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            System.err.println("[SHUTDOWN] " + name + " failed to stop cleanly: " + e);
        }
    }
}
