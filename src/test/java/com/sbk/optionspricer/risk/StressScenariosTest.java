package com.sbk.optionspricer.risk;

import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.execution.Valuation.PositionValuation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The book revalued from scratch under shocks to spot, volatility, skew and term structure, which the Greeks-only stress cannot see. */
class StressScenariosTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 11, 20);

    private static PositionValuation option(OptionType type, double strike, int quantity, double iv) {
        String symbol = com.sbk.optionspricer.instruments.OccSymbol.format("SPY", EXPIRY, type, strike);
        return new PositionValuation(symbol, "OPTION", quantity, 100, 5.0, "MID", 5.0, 0.0, iv, "MARKET_IV", 0, 0, 0, 0, 0, false, "");
    }

    private static PositionValuation shares(int quantity) {
        return new PositionValuation("SPY", "STOCK", quantity, 1, 100.0, "SPOT", 100.0, 0.0, Double.NaN, "NONE", 1, 0, 0, 0, 0, false, "");
    }

    private static StressScenarios.Result run(List<PositionValuation> book) {
        return StressScenarios.run(book, 100.0, TODAY, 0.045, 0.013);
    }

    private static double pnl(StressScenarios.Result r, String name) {
        return r.scenarios().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow().pnl();
    }

    @Test
    void aLongStraddleGainsOnABigMoveEitherWayAndOnRisingVolButLosesOnFallingVol() {
        List<PositionValuation> straddle = List.of(option(OptionType.CALL, 100, 1, 0.20), option(OptionType.PUT, 100, 1, 0.20));

        StressScenarios.Result r = run(straddle);

        assertTrue(pnl(r, "spot -10%") > 0.0 && pnl(r, "spot +10%") > 0.0, r.toString());
        assertTrue(pnl(r, "vol +10 pts") > 0.0, r.toString());
        assertTrue(pnl(r, "vol -10 pts") < 0.0, r.toString());
        assertEquals(0, r.unvalued().size());
    }

    @Test
    void aShortStraddleIsTheMirrorImage() {
        List<PositionValuation> longBook = List.of(option(OptionType.CALL, 100, 1, 0.20), option(OptionType.PUT, 100, 1, 0.20));
        List<PositionValuation> shortBook = List.of(option(OptionType.CALL, 100, -1, 0.20), option(OptionType.PUT, 100, -1, 0.20));

        for (StressScenarios.Scenario s : run(longBook).scenarios()) {
            assertEquals(-s.pnl(), pnl(run(shortBook), s.name()), 1e-9, s.name());
        }
    }

    @Test
    void sharesMoveOneForOneWithSpotAndNotAtAllWithVol() {
        StressScenarios.Result r = run(List.of(shares(50)));

        assertEquals(50 * -10.0, pnl(r, "spot -10%"), 1e-9);
        assertEquals(50 * 10.0, pnl(r, "spot +10%"), 1e-9);
        assertEquals(0.0, pnl(r, "vol +10 pts"), 1e-12);
        assertEquals(0.0, pnl(r, "skew steepens"), 1e-12);
    }

    @Test
    void aSkewShockHurtsALongOutOfTheMoneyCallAndHelpsALongOutOfTheMoneyPut() {
        StressScenarios.Result call = run(List.of(option(OptionType.CALL, 110, 1, 0.20)));
        StressScenarios.Result put = run(List.of(option(OptionType.PUT, 90, 1, 0.20)));

        assertTrue(pnl(call, "skew steepens") < 0.0, "the upside wing gets cheaper: " + call);
        assertTrue(pnl(put, "skew steepens") > 0.0, "the downside wing gets dearer: " + put);
    }

    @Test
    void aFrontMonthShockHitsShortDatedOptionsHarderThanLongDatedOnes() {
        PositionValuation near = option(OptionType.CALL, 100, 1, 0.20);
        String farSymbol = com.sbk.optionspricer.instruments.OccSymbol.format("SPY", LocalDate.of(2027, 10, 15), OptionType.CALL, 100);
        PositionValuation far = new PositionValuation(farSymbol, "OPTION", 1, 100, 9.0, "MID", 9.0, 0.0, 0.20, "MARKET_IV", 0, 0, 0, 0, 0, false, "");

        double nearGain = pnl(run(List.of(near)), "front-month vol +10 pts");
        double farGain = pnl(run(List.of(far)), "front-month vol +10 pts");

        assertTrue(nearGain > 0.0 && farGain >= 0.0);
        assertTrue(nearGain / 100 > 0, "per-share gain is positive");
        // vega of a 1-year option is larger, but the shock decays with maturity: the one-year gain per share is well under the full 10 points of vega
        double farFullVol = pnl(run(List.of(far)), "vol +10 pts");
        assertTrue(farGain < 0.5 * farFullVol, "front-month shock is mostly gone a year out: " + farGain + " vs " + farFullVol);
    }

    @Test
    void aPositionWithoutAVolatilityIsListedAsUnvaluedNotAssumedFlat() {
        PositionValuation noVol = new PositionValuation(com.sbk.optionspricer.instruments.OccSymbol.format("SPY", EXPIRY, OptionType.CALL, 100), "OPTION", 1, 100,
                5.0, "MID", 5.0, 0.0, Double.NaN, "NONE", Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false, "");
        PositionValuation expired = new PositionValuation(com.sbk.optionspricer.instruments.OccSymbol.format("SPY", LocalDate.of(2026, 10, 1), OptionType.CALL, 100),
                "OPTION", 1, 100, 0.0, "INTRINSIC", 0.0, 0.0, Double.NaN, "EXPIRED", 0, 0, 0, 0, 0, true, "");

        StressScenarios.Result r = run(List.of(noVol, expired, shares(10)));

        assertEquals(2, r.unvalued().size(), r.toString());
        assertEquals(10 * -10.0, pnl(r, "spot -10%"), 1e-9, "the share position is still stressed");
    }

    @Test
    void theWorstScenarioIsNamedAndAnEmptyBookStressesToZero() {
        StressScenarios.Result r = run(List.of(option(OptionType.CALL, 100, -1, 0.20)));
        assertTrue(r.worst().pnl() < 0.0);
        assertEquals(r.scenarios().stream().mapToDouble(StressScenarios.Scenario::pnl).min().orElseThrow(), r.worst().pnl(), 1e-12);

        StressScenarios.Result empty = run(List.of());
        assertEquals(0.0, empty.worst().pnl(), 1e-12);
    }
}
