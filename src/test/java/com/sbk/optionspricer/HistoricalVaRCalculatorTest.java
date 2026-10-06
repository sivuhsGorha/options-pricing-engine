package com.sbk.optionspricer;

import com.sbk.optionspricer.risk.HistoricalVaRCalculator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class HistoricalVaRCalculatorTest {

    @Test
    void testAllProfitableSampleDoesNotReportPositiveLoss() {
        // Portfolio made profit on all days (10.0, 20.0, 30.0, ..., 100.0)
        double[] profitablePnL = new double[100];
        for (int i = 0; i < 100; i++) {
            profitablePnL[i] = 10.0 + i;
        }

        double var99 = HistoricalVaRCalculator.calculate99PercentVaR(profitablePnL);
        assertEquals(0.0, var99, 1e-9, "An all-profitable sample must report 0.0 loss, never positive loss");
    }

    @Test
    void testNegativePnLCalculatesPositiveLoss() {
        // 100 days of PnL: worst day is -500.0
        double[] pnl = new double[100];
        pnl[0] = -500.0;
        for (int i = 1; i < 100; i++) {
            pnl[i] = 10.0 * i;
        }

        double var99 = HistoricalVaRCalculator.calculate99PercentVaR(pnl);
        assertEquals(500.0, var99, 1e-9, "99% VaR for worst loss of -500.0 should be 500.0");
    }

    @Test
    void testInvalidSampleRejection() {
        assertThrows(IllegalArgumentException.class, () ->
                HistoricalVaRCalculator.calculate99PercentVaR(new double[]{10.0, 20.0}),
                "Sample size under minimum threshold must throw IllegalArgumentException");
    }
}
