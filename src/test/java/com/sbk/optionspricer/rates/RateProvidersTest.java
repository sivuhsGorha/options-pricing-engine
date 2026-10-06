package com.sbk.optionspricer.rates;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RateProvidersTest {

    private static String fredBody(String value) {
        return "{\"realtime_start\":\"2025-03-03\",\"observations\":[{\"date\":\"2025-03-03\",\"value\":\"" + value + "\"}]}";
    }

    // ---------------- FRED Treasury par yields ----------------

    @Test
    void treasuryParYieldsAreBootstrappedAsSemiannualCouponBondsNotUsedAsContinuousZeroRates() throws Exception {
        // A flat 4.00% semiannual par curve has Z(t) = (1 + 0.04/2)^(-2t). Treating 4% as a continuously
        // compounded zero rate would give exp(-0.04 t), which is wrong by ~0.04% of price per year.
        FredYieldCurve fred = new FredYieldCurve(() -> "key", series -> fredBody("4.00"));

        YieldCurve curve = fred.getYieldCurve();

        for (double t : new double[]{1, 2, 3, 5, 7, 10, 20, 30}) {
            assertEquals(Math.pow(1.02, -2 * t), curve.getDiscountFactor(t), 1e-10, "t=" + t);
        }
        assertEquals(Math.pow(1.02, -9), curve.getDiscountFactor(4.5), 1e-10, "between pillars");
        assertTrue(Math.abs(curve.getDiscountFactor(10) - Math.exp(-0.04 * 10)) > 1e-3, "must differ from the naive continuous-rate curve");
    }

    @Test
    void billsPayOnceAtMaturity() throws Exception {
        YieldCurve curve = new FredYieldCurve(() -> "key", series -> fredBody("4.00")).getYieldCurve();

        assertEquals(1.0 / (1.0 + 0.04 / 12.0), curve.getDiscountFactor(1.0 / 12.0), 1e-12);
        assertEquals(1.0 / (1.0 + 0.04 * 0.25), curve.getDiscountFactor(0.25), 1e-12);
    }

    @Test
    void zeroAndMissingObservationsAreHandled() throws Exception {
        Map<String, String> bodies = Map.of(
                "DGS1MO", fredBody("0.00"),     // a genuine zero rate is data, not an error
                "DGS3MO", fredBody("."),        // FRED's marker for a missing observation
                "DGS1", fredBody("3.00"));
        FredYieldCurve fred = new FredYieldCurve(() -> "key", series -> {
            String body = bodies.get(series);
            if (body == null) throw new IOException("no data");
            return body;
        });

        YieldCurve curve = fred.getYieldCurve();

        assertEquals(1.0, curve.getDiscountFactor(1.0 / 12.0), 1e-14, "0% bill: Z = 1");
        assertTrue(curve.getDiscountFactor(1.0) < 1.0);
    }

    @Test
    void noDataAtAllIsAnErrorAndNeverLeaksTheApiKey() {
        FredYieldCurve fred = new FredYieldCurve(() -> "TOPSECRETKEY", series -> {
            throw new IOException("GET https://api.stlouisfed.org/...&api_key=TOPSECRETKEY failed");
        });

        RuntimeException e = assertThrows(RuntimeException.class, fred::getYieldCurve);
        assertFalse(String.valueOf(e.getMessage()).contains("TOPSECRETKEY"), e.getMessage());
        assertNull(e.getCause(), "the cause carries the URL, which contains the key");
    }

    @Test
    void aMissingKeyIsReportedBeforeAnyRequest() {
        FredYieldCurve fred = new FredYieldCurve(() -> null, series -> {
            fail("no request may be made without a key");
            return "";
        });
        assertThrows(IllegalStateException.class, fred::getYieldCurve);
    }

    // ---------------- ECB euro short-term rate ----------------

    private static final String ECB_BODY =
            "{\"dataSets\":[{\"action\":\"Replace\",\"series\":{\"0:0:0:0:0\":{\"attributes\":[0],\"observations\":{\"0\":[3.896,0,0]}}}}]}";

    @Test
    void theEsterAnchorIsParsedFromTheSdmxStructure() throws Exception {
        EsterRateProvider provider = new EsterRateProvider(() -> ECB_BODY);

        YieldCurve curve = provider.getYieldCurve();

        // The shortest pillar is a one-month swap; recover its rate from the discount factor: R = (1/Z - 1) / T.
        double oneMonth = 1.0 / 12.0;
        double impliedSwapRate = (1.0 / curve.getDiscountFactor(oneMonth) - 1.0) / oneMonth;
        assertEquals(0.03896 + 0.02 * (1 - Math.exp(-0.5 * oneMonth)), impliedSwapRate, 1e-12);
    }

    @Test
    void theCurveShapeIsLabelledSyntheticBecauseOnlyTheOvernightRateIsReal() {
        assertFalse(new EsterRateProvider(() -> ECB_BODY).isMarketData(), "swap rates are generated from a formula, not quoted");
        assertTrue(new FredYieldCurve(() -> "k", s -> fredBody("4.0")).isMarketData());
    }

    @Test
    void malformedEcbResponsesFailClearly() {
        for (String body : new String[]{"", "not json", "{}", "{\"dataSets\":[]}", "{\"dataSets\":[{\"series\":{}}]}",
                "{\"dataSets\":[{\"series\":{\"0\":{\"observations\":{\"0\":[\"abc\"]}}}}]}"}) {
            assertThrows(RuntimeException.class, () -> new EsterRateProvider(() -> body).getYieldCurve(), body);
        }
    }
}
