package com.sbk.optionspricer.execution;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionReportTest {

    private static final Instant T0 = Instant.parse("2026-10-15T13:30:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 15);

    private static DecisionJournal.Row row(int seconds, String action, String reason, double edge, String orders) {
        return new DecisionJournal.Row(T0.plusSeconds(seconds), action, "2026-11-13", 769.0, 0.14, 0.14 - edge, edge, 0, orders, reason);
    }

    private static DecisionJournal.Row hold(int seconds, double edgePoints) {
        return row(seconds, "HOLD", "edge within the 1.00-point band | RV forecast 13.7%, VRP +0.3 pts", edgePoints / 100.0, "");
    }

    private static DecisionJournal.Row waitStale(int seconds, int age) {
        return row(seconds, "WAIT", "SPY261113C00769000: quote STALE (feed " + age + " s old, limit 120 s)", Double.NaN, "");
    }

    private static DecisionJournal.Row waitSpot(int seconds) {
        return row(seconds, "WAIT", "spot quote STALE", Double.NaN, "");
    }

    private List<DecisionJournal.Row> session() {
        List<DecisionJournal.Row> rows = new ArrayList<>();
        rows.add(waitSpot(0));
        rows.add(waitStale(30, 130));
        rows.add(waitStale(60, 160));
        rows.add(hold(90, -0.20));
        rows.add(hold(120, 0.40));
        rows.add(hold(150, -0.60));
        // a five-minute silence, then three more steps
        rows.add(hold(450, 0.20));
        rows.add(waitStale(480, 190));
        rows.add(row(510, "BUY_STRADDLE_REJECTED", "market cheap", -0.02, "BUY 1 SPY261113C00769000 rejected: x"));
        // the next day must not be counted
        rows.add(new DecisionJournal.Row(T0.plusSeconds(86_400), "HOLD", "2026-11-13", 769.0, 0.14, 0.14, 0.0, 0, "", "x"));
        return rows;
    }

    @Test
    void talliesTheDayByWhatTheStrategyDid() {
        String report = SessionReport.build(session(), List.of(), DAY);

        assertTrue(report.contains("SESSION REPORT 2026-10-15"), report);
        assertTrue(report.contains("9 decisions"), "only that day's rows: " + report);
        assertTrue(report.contains("HOLD 4"), report);
        assertTrue(report.contains("option quote STALE 3"), report);
        assertTrue(report.contains("spot STALE 1"), report);
        assertTrue(report.contains("orders attempted 1"), report);
    }

    @Test
    void reportsHowOldTheStaleQuotesWereAndWhatTheEdgeOnHoldsWas() {
        String report = SessionReport.build(session(), List.of(), DAY);

        assertTrue(report.contains("feed age of stale option quotes: 3 waits, 130 to 190 s, mean 160 s"), report);
        assertTrue(report.contains("edge on holds (points): -0.60 to +0.40, mean -0.05"), report);
        assertTrue(report.contains("RV readout on 4 of 4 holds"), report);
    }

    @Test
    void namesTheGapsAndTheCoverageSoAShortSessionLooksShort() {
        String report = SessionReport.build(session(), List.of(), DAY);

        assertTrue(report.contains("gap of 5.0 min"), report);
        assertTrue(report.contains("13:32:30") && report.contains("13:37:30"), "from and to: " + report);
        assertTrue(report.contains("13:30:00") && report.contains("13:38:30"), "first and last: " + report);
        assertTrue(report.contains("coverage"), report);
    }

    @Test
    void addsTheDaysPnlAndSpotFromTheSamplesWhenThereAreSome() {
        List<SessionReport.PnlSample> pnl = List.of(
                new SessionReport.PnlSample(T0, 770.0, 0.0, 100.0),
                new SessionReport.PnlSample(T0.plusSeconds(3600), 767.0, 0.0, 160.0),
                new SessionReport.PnlSample(T0.plusSeconds(86_400), 700.0, 0.0, 999.0)); // another day
        String report = SessionReport.build(session(), pnl, DAY);

        assertTrue(report.contains("P&L: 100.00 to 160.00"), report);
        assertTrue(report.contains("spot 770.00 to 767.00"), report);
    }

    @Test
    void anEmptyDayIsSaidPlainlyNotReportedAsAQuietOne() {
        String report = SessionReport.build(session(), List.of(), LocalDate.of(2026, 10, 20));

        assertTrue(report.contains("no decisions recorded"), report);
        assertFalse(report.contains("HOLD"), report);
    }

    @Test
    void reportsDataFreshnessAndAgeDistributionsAcrossDecisions() {
        List<DecisionJournal.Row> rows = List.of(
                new DecisionJournal.Row(T0, "WAIT", "", Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, "", "spot quote STALE", 15.0, Double.NaN, Double.NaN),
                new DecisionJournal.Row(T0.plusSeconds(30), "WAIT", "2026-11-13", 769.0, Double.NaN, Double.NaN, Double.NaN, 0, "", "quote STALE (feed 140 s old)", 0.8, 140.0, 140.0),
                new DecisionJournal.Row(T0.plusSeconds(60), "HOLD", "2026-11-13", 769.0, 0.14, 0.142, -0.002, 0, "", "within band", 0.5, 45.0, 2.5),
                new DecisionJournal.Row(T0.plusSeconds(90), "HOLD", "2026-11-13", 769.0, 0.14, 0.141, -0.001, 0, "", "within band", 0.6, 55.0, 3.1)
        );

        String report = SessionReport.build(rows, List.of(), DAY);
        assertTrue(report.contains("quote & chain age (s):"), report);
        assertTrue(report.contains("spot mean"), report);
        assertTrue(report.contains("chain mean"), report);
        assertTrue(report.contains("option quote mean"), report);
        assertTrue(report.contains("stale gate blocked: 2 decisions (50.0% of steps)"), report);
    }

    @Test
    void reportsHowWideTheOptionSpreadsWereAndHowOftenTheyWereOverTheEntryLimit() {
        List<DecisionJournal.Row> rows = List.of(
                new DecisionJournal.Row(T0, "HOLD", "2026-11-13", 769.0, 0.14, 0.141, -0.001, 0, "", "x", 1.0, 45.0, 1.0, 80.0),
                new DecisionJournal.Row(T0.plusSeconds(30), "HOLD", "2026-11-13", 769.0, 0.14, 0.141, -0.001, 0, "", "x", 1.0, 45.0, 1.0, 150.0),
                new DecisionJournal.Row(T0.plusSeconds(60), "HOLD", "2026-11-13", 769.0, 0.14, 0.141, -0.001, 0, "", "x", 1.0, 45.0, 1.0, 315.0),
                new DecisionJournal.Row(T0.plusSeconds(90), "WAIT", "", Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, "", "y")
        );

        String report = SessionReport.build(rows, List.of(), DAY);

        assertTrue(report.contains("option spread (bps, wider leg): mean 182 (80 to 315)"), report);
        assertTrue(report.contains("1 of 3 above the 200 bps entry limit"), report);
        // no spread recorded at all (older journals): the line is simply absent
        assertFalse(SessionReport.build(List.of(rows.get(3)), List.of(), DAY).contains("option spread"));
    }
}
