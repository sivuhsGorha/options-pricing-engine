package com.sbk.optionspricer.volatility;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

class RealizedVolForecastTest {

    @Test
    void ewmaCalculatesExpectedVolatilityFromSyntheticReturns() {
        // Daily returns corresponding to ~20% annualized volatility
        double dailyVol = 0.20 / Math.sqrt(252.0);
        double[] returns = new double[60];
        for (int i = 0; i < returns.length; i++) {
            returns[i] = (i % 2 == 0 ? 1.0 : -1.0) * dailyVol;
        }

        double vol = RealizedVolForecast.ewmaVol(returns, 0.94, 252.0);
        assertEquals(0.20, vol, 0.01, "EWMA vol should recover ~20% annualised volatility");
    }

    @Test
    void harForecastProducesSensibleForecastGivenHistoricalReturns() {
        double[] dailyReturns = new double[30];
        // 16% annualized daily vol
        double dailyVol = 0.16 / Math.sqrt(252.0);
        for (int i = 0; i < dailyReturns.length; i++) {
            dailyReturns[i] = (i % 2 == 0 ? 1.0 : -1.0) * dailyVol;
        }

        double forecast = RealizedVolForecast.harForecast(dailyReturns);
        assertTrue(forecast > 0.05 && forecast < 0.40, "HAR forecast must be within sensible market bounds: " + forecast);
    }

    @Test
    void termStructureAdjustsTowardsLongTermUnconditionalVol() {
        double shortVol = 0.35; // high short term vol
        double termVol1Year = RealizedVolForecast.termStructureAdjust(shortVol, 1.0);
        assertTrue(termVol1Year < shortVol, "1-year vol should mean-revert down towards 18%");
        assertTrue(termVol1Year > RealizedVolForecast.LONG_TERM_UNCONDITIONAL_VOL);

        double lowShortVol = 0.10;
        double termVolLow = RealizedVolForecast.termStructureAdjust(lowShortVol, 1.0);
        assertTrue(termVolLow > lowShortVol, "1-year vol should mean-revert up towards 18%");
    }

    @Test
    void varianceRiskPremiumIdentifiesRichAndCheapImpliedVol() {
        double iv = 0.22;
        double rvForecast = 0.18;
        double vrp = RealizedVolForecast.varianceRiskPremium(iv, rvForecast);
        assertEquals(0.04, vrp, 1e-6, "VRP should be positive 4 points");

        double cheapIv = 0.14;
        double cheapVrp = RealizedVolForecast.varianceRiskPremium(cheapIv, rvForecast);
        assertEquals(-0.04, cheapVrp, 1e-6, "VRP should be negative 4 points");
    }

    private static void feedDailyCloses(RealizedVolForecast forecast, int closes) {
        double price = 500.0;
        for (int i = 0; i < closes; i++) {
            price *= (1.0 + (i % 2 == 0 ? 0.005 : -0.004));
            forecast.observeSpot(price);
        }
    }

    @Test
    void aHarForecastWithTooFewDaysSaysNothingInsteadOfBecomingAnEwmaUnderTheSameName() {
        RealizedVolForecast har = new RealizedVolForecast(RealizedVolForecast.Model.HAR);
        RealizedVolForecast ewma = new RealizedVolForecast(RealizedVolForecast.Model.EWMA);
        feedDailyCloses(har, 16);   // 15 returns: enough for EWMA, not for the 22-day HAR window
        feedDailyCloses(ewma, 16);
        LocalDate expiry = LocalDate.now().plusDays(30);

        assertTrue(har.fairVol("SPY", expiry, 500.0, 30.0 / 365.25).isEmpty(), "HAR needs 22 daily returns");
        assertTrue(ewma.fairVol("SPY", expiry, 500.0, 30.0 / 365.25).isPresent());

        feedDailyCloses(har, 10);   // now 26 closes in the history
        assertTrue(har.fairVol("SPY", expiry, 500.0, 30.0 / 365.25).isPresent());
    }

    @Test
    void anEwmaOverAHandfulOfReturnsIsNotOffered() {
        RealizedVolForecast ewma = new RealizedVolForecast(RealizedVolForecast.Model.EWMA);
        feedDailyCloses(ewma, 7);   // 6 returns: the first squared return would still be most of the estimate

        assertTrue(ewma.fairVol("SPY", LocalDate.now().plusDays(30), 500.0, 30.0 / 365.25).isEmpty());
    }

    @Test
    void fairVolProviderYieldsValuesWithSufficientPriceHistory() {
        RealizedVolForecast forecast = new RealizedVolForecast(RealizedVolForecast.Model.EWMA);
        LocalDate expiry = LocalDate.now().plusDays(30);

        OptionalDouble empty = forecast.fairVol("SPY", expiry, 500.0, 30.0 / 365.25);
        assertTrue(empty.isEmpty(), "Must be empty without prices");

        // Feed 20 days of simulated prices
        double price = 500.0;
        for (int i = 0; i < 25; i++) {
            price *= (1.0 + (i % 2 == 0 ? 0.005 : -0.004));
            forecast.observeSpot(price);
        }

        OptionalDouble fair = forecast.fairVol("SPY", expiry, 500.0, 30.0 / 365.25);
        assertTrue(fair.isPresent(), "Must have fair vol after history is accumulated");
        assertTrue(fair.getAsDouble() > 0.02 && fair.getAsDouble() < 0.50);
    }
}
