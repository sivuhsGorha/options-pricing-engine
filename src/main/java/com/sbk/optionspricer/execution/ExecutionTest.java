package com.sbk.optionspricer.execution;

public class ExecutionTest {

    public static void main(String[] args) {
        System.out.println("=== Phase 3: Pre-Trade Risk & Execution Engine ===\n");
        
        // Setup Risk Filter: Max 500 contracts, Max $1,000,000 notional, Max 10 msgs/sec
        PreTradeRiskFilter filter = new PreTradeRiskFilter(500, 1_000_000.0, 10);
        SmartOrderRouter sor = new SmartOrderRouter(filter);
        
        System.out.println("Testing Normal Order (Pass):");
        Order normalOrder = new Order(450000, true, 50, 15.50);
        boolean p1 = sor.routeOrder(normalOrder);
        System.out.println("Result: " + (p1 ? "ROUTED" : "REJECTED") + "\n");
        
        System.out.println("Testing Fat-Finger Quantity (Reject):");
        Order fatFingerQty = new Order(450000, false, 5000, 15.50); // 5000 > 500 max
        boolean p2 = sor.routeOrder(fatFingerQty);
        System.out.println("Result: " + (p2 ? "ROUTED" : "REJECTED") + "\n");
        
        System.out.println("Testing Fat-Finger Price/Notional (Reject):");
        // User accidentally types 5000.50 instead of 15.50, causing notional to explode
        Order fatFingerPrice = new Order(450000, true, 500, 5000.50); 
        boolean p3 = sor.routeOrder(fatFingerPrice);
        System.out.println("Result: " + (p3 ? "ROUTED" : "REJECTED") + "\n");
        
        System.out.println("Testing Message Throttle/Spam (Reject):");
        int routedCount = 0;
        int rejectedCount = 0;
        // Try to send 15 orders instantly (limit is 10/sec)
        for (int i = 0; i < 15; i++) {
            boolean success = sor.routeOrder(new Order(450000, true, 1, 15.50));
            if (success) routedCount++;
            else rejectedCount++;
        }
        System.out.printf("Throttle Test Results: %d routed, %d rejected%n", routedCount, rejectedCount);
    }
}
