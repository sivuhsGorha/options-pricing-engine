package com.sbk.optionspricer.risk;

import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.OptionParameters;
import com.sbk.optionspricer.BlackScholesPricer;
import com.sbk.optionspricer.risk.greeks.AnalyticalHigherGreeks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GreekRiskMonitorTest {
    @Test
    void computesAggregateRiskAndDetectsLimitBreaches() {
        GreekRiskMonitor monitor = new GreekRiskMonitor(5_000_000.0, 250_000.0, 100_000.0, 50_000.0, 25_000.0);

        PortfolioPosition longCall = new PortfolioPosition("SPY", 100, 100);
        longCall.updateGreeks(0.62, 0.035, 46.0);

        PortfolioPosition shortPut = new PortfolioPosition("QQQ", -80, 100);
        shortPut.updateGreeks(-0.48, 0.028, 39.0);

        monitor.registerPosition(longCall);
        monitor.registerPosition(shortPut);

        GreekRiskMonitor.PortfolioRisk risk = monitor.snapshot();
        assertTrue(risk.netDelta() != 0.0, "portfolio delta should be non-zero");
        assertTrue(risk.netGamma() != 0.0, "portfolio gamma should be non-zero");
        assertTrue(risk.vanna() != 0.0 || risk.volga() != 0.0 || risk.charm() != 0.0, "higher-order Greeks should be populated");

        assertFalse(monitor.enforceLimits(), "within-limit portfolio should pass check");

        monitor.registerPosition(new PortfolioPosition("SPY", 100_000, 100));
        assertTrue(monitor.enforceLimits(), "extreme position should breach risk limits");
    }
}
