package com.sbk.optionspricer.risk;

public class RiskEngineTest {

    public static void main(String[] args) {
        System.out.println("=== Phase 4: Enterprise Risk Engine ===\n");
        
        // Firm Risk Limits: Max Net Delta = 50,000 shares, Max Vega = $10,000 per 1% vol change
        GreekAggregator riskEngine = new GreekAggregator(50000.0, 10000.0);
        
        // Let's add some live positions
        System.out.println("Booking Trades...");
        
        // Short 1000 Call Options (Multiplier 100) -> Massive negative Gamma risk
        PortfolioPosition calls = new PortfolioPosition("SPY_CALL_500", -1000, 100);
        calls.updateGreeks(0.50, 0.05, 10.0);
        riskEngine.addPosition(calls);
        
        // Long 500 Put Options (Multiplier 100)
        PortfolioPosition puts = new PortfolioPosition("SPY_PUT_450", 500, 100);
        puts.updateGreeks(-0.25, 0.03, 12.0); // Puts have negative Delta, positive Vega
        riskEngine.addPosition(puts);
        
        System.out.println("\n--- Real-Time Portfolio Greeks ---");
        System.out.printf("Net Delta : %,.2f shares%n", riskEngine.calculateNetDelta());
        System.out.printf("Net Gamma : %,.2f%n", riskEngine.calculateNetGamma());
        System.out.printf("Net Vega  : $%,.2f%n", riskEngine.calculateNetVega());
        
        System.out.println("\n--- Macro Limit Check ---");
        boolean isSafe = riskEngine.checkMacroLimits();
        if (isSafe) {
            System.out.println("STATUS: ALL SYSTEMS GREEN. Within Risk Limits.");
        }
        
        System.out.println("\n--- SPAN / Eurex Prisma Margin Simulator ---");
        double spotPrice = 500.0;
        double initialMargin = SpanMarginApproximation.calculateInitialMargin(riskEngine, spotPrice);
        System.out.printf("Required Clearinghouse Margin: $%,.2f%n", initialMargin);
        
        System.out.println("\n--- Historical Value-at-Risk (VaR) ---");
        // Simulate 252 days of historical daily PnL vectors (1 year of trading)
        double[] historicalPnL = new double[252];
        for (int i = 0; i < 252; i++) {
            // Randomly generate historical daily PnL between -$50k and +$50k
            historicalPnL[i] = (Math.random() * 100000) - 50000;
        }
        // Inject a few terrible market crash days
        historicalPnL[10] = -850000.0; 
        historicalPnL[50] = -1200000.0;
        
        double var99 = HistoricalVaRCalculator.calculate99PercentVaR(historicalPnL);
        System.out.printf("99%% Confidence Historical 1-Day VaR: $%,.2f%n", var99);
        
        System.out.println("\n--- Event-Driven Backtester (Almgren-Chriss Impact) ---");
        // We want to dump 50,000 contracts into a market with 1,000,000 ADV over 10% of the day
        double slippage = EventDrivenBacktester.calculateAlmgrenChrissImpact(50000, 1000000, 0.15, 0.10);
        System.out.printf("Estimated Market Impact (Slippage) for 50k order: $%,.4f per contract%n", slippage);
        EventDrivenBacktester.simulateQueuePosition(50000, 125000);
        
        System.out.println("\n--- Hardware Kill Switch ---");
        // If the Delta exceeds the firm limit, we trigger the UDP kill switch
        if (!isSafe) {
            HardwareKillSwitch.triggerMassCancel(999); // Trader ID 999
        }
    }
}
