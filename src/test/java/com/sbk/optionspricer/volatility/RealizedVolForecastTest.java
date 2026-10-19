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

    @Test
    void calibrateHarFallsBackToDefaultWhenInsufficientData() {
        double[] tooFew = new double[20];
        RealizedVolForecast.HarCoefficients coeffs = RealizedVolForecast.calibrateHar(tooFew);
        assertEquals(RealizedVolForecast.HAR_BETA_0, coeffs.beta0(), 1e-9);
        assertEquals(RealizedVolForecast.HAR_BETA_DAILY, coeffs.betaDaily(), 1e-9);
        assertEquals(RealizedVolForecast.HAR_BETA_WEEKLY, coeffs.betaWeekly(), 1e-9);
        assertEquals(RealizedVolForecast.HAR_BETA_MONTHLY, coeffs.betaMonthly(), 1e-9);
        assertTrue(Double.isNaN(coeffs.rSquared()));
    }

    @Test
    void calibrateHarRecoversSyntheticCoefficientsFromSimulatedProcess() {
        // Generate a 200-day series where RV follows exact HAR linear model
        int n = 200;
        double[] returns = new double[n];
        double trueBeta0 = 0.02;
        double trueDaily = 0.35;
        double trueWeekly = 0.30;
        double trueMonthly = 0.20;

        // Initialize with baseline 16% annualized daily vol
        double baseVol = 0.16 / Math.sqrt(252.0);
        for (int i = 0; i < 22; i++) {
            returns[i] = (i % 2 == 0 ? 1.0 : -1.0) * baseVol;
        }
        for (int i = 22; i < n; i++) {
            double rvD = Math.abs(returns[i - 1]) * Math.sqrt(252.0);
            double sumW = 0.0;
            for (int k = i - 5; k < i; k++) sumW += returns[k] * returns[k];
            double rvW = Math.sqrt((sumW / 5.0) * 252.0);
            double sumM = 0.0;
            for (int k = i - 22; k < i; k++) sumM += returns[k] * returns[k];
            double rvM = Math.sqrt((sumM / 22.0) * 252.0);

            double targetVol = trueBeta0 + trueDaily * rvD + trueWeekly * rvW + trueMonthly * rvM;
            double dailySigma = Math.max(0.01, targetVol) / Math.sqrt(252.0);
            returns[i] = (i % 2 == 0 ? 1.0 : -1.0) * dailySigma;
        }

        RealizedVolForecast.HarCoefficients fitted = RealizedVolForecast.calibrateHar(returns);
        assertNotNull(fitted);
        assertEquals(trueBeta0, fitted.beta0(), 0.05, "Recovered beta0: " + fitted.beta0());
        assertEquals(trueDaily, fitted.betaDaily(), 0.08, "Recovered betaDaily: " + fitted.betaDaily());
        assertEquals(trueWeekly, fitted.betaWeekly(), 0.08, "Recovered betaWeekly: " + fitted.betaWeekly());
        assertEquals(trueMonthly, fitted.betaMonthly(), 0.08, "Recovered betaMonthly: " + fitted.betaMonthly());
        assertTrue(fitted.rSquared() > 0.80, "R-squared must be high on clean process: " + fitted.rSquared());
    }

    @Test
    void calibratedHarCoefficientsCanBeInjectedIntoForecastEngine() {
        RealizedVolForecast.HarCoefficients custom = new RealizedVolForecast.HarCoefficients(0.01, 0.40, 0.30, 0.20, 0.45);
        RealizedVolForecast forecast = new RealizedVolForecast(RealizedVolForecast.Model.HAR, RealizedVolForecast.DEFAULT_EWMA_LAMBDA, 500, custom);
        feedDailyCloses(forecast, 30);
        LocalDate expiry = LocalDate.now().plusDays(30);

        OptionalDouble fair = forecast.fairVol("SPY", expiry, 500.0, 30.0 / 365.25);
        assertTrue(fair.isPresent());
        assertTrue(fair.getAsDouble() > 0.01 && fair.getAsDouble() < 1.0);
    }

    // ------------------------------------------------------------------ HAR fitted on variance

    private static double[] gaussianReturns(int n, double dailySigma, long seed) {
        java.util.Random random = new java.util.Random(seed);
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = dailySigma * random.nextGaussian();
        return r;
    }

    @Test
    void aHarFittedOnVarianceForecastsTheRightLevelWhereTheAbsoluteReturnFitRunsLow() {
        double sigma = 0.01;
        double trueVol = sigma * Math.sqrt(252.0);
        double[] returns = gaussianReturns(6000, sigma, 5);

        RealizedVolForecast.HarCoefficients onVariance = RealizedVolForecast.calibrateHarVariance(returns);
        RealizedVolForecast.HarCoefficients onAbsolute = RealizedVolForecast.calibrateHar(returns);

        assertTrue(onVariance.varianceSpace());
        double fitted = RealizedVolForecast.harForecast(returns, onVariance);
        assertEquals(trueVol, fitted, 0.06 * trueVol, "variance-space fit should recover the volatility level, got " + fitted);
        // why it was replaced: absolute daily returns average about 0.8 of the volatility, and the fit inherits that
        assertTrue(RealizedVolForecast.harForecast(returns, onAbsolute) < 0.90 * trueVol,
                "the absolute-return fit runs low: " + RealizedVolForecast.harForecast(returns, onAbsolute));
    }

    @Test
    void theVarianceFitFallsBackToTheDefaultWeightsWhenItHasNothingToFit() {
        assertEquals(RealizedVolForecast.HarCoefficients.DEFAULT, RealizedVolForecast.calibrateHarVariance(gaussianReturns(30, 0.01, 1)));
        assertEquals(RealizedVolForecast.HarCoefficients.DEFAULT, RealizedVolForecast.calibrateHarVariance(null));
        double[] constant = new double[200];
        for (int i = 0; i < constant.length; i++) constant[i] = (i % 2 == 0 ? 1 : -1) * 0.01;
        assertEquals(RealizedVolForecast.HarCoefficients.DEFAULT, RealizedVolForecast.calibrateHarVariance(constant),
                "identical days make the regressors collinear: no fit, not a made-up one");
    }

    @Test
    void theVarianceFitReportsAnRSquaredInRangeAndANeverNonsenseForecast() {
        // volatility clusters: calm, turbulent, calm
        java.util.Random random = new java.util.Random(9);
        double[] returns = new double[600];
        for (int i = 0; i < returns.length; i++) {
            double sigma = (i / 100) % 2 == 0 ? 0.005 : 0.02;
            returns[i] = sigma * random.nextGaussian();
        }

        RealizedVolForecast.HarCoefficients fit = RealizedVolForecast.calibrateHarVariance(returns);

        assertTrue(fit.varianceSpace());
        assertTrue(fit.rSquared() >= 0.0 && fit.rSquared() <= 1.0, "R^2 " + fit.rSquared());
        double forecast = RealizedVolForecast.harForecast(returns, fit);
        assertTrue(forecast >= 0.01 && forecast <= 2.0, "forecast " + forecast);

        // weights that would send the variance negative are floored, not square-rooted into NaN
        RealizedVolForecast.HarCoefficients negative = new RealizedVolForecast.HarCoefficients(-5.0, 0.0, 0.0, 0.0, 0.5, true);
        assertEquals(0.01, RealizedVolForecast.harForecast(returns, negative), 1e-12);
    }
}
