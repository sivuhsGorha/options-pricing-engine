package com.sbk.optionspricer.alert;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The phone hears from the engine when it starts, when the market opens, and when it closes, on weekdays, once each. */
class StatusDigestTest {

    private record Sent(String title, String message, String priority) {}

    private static final class MovableClock extends Clock {
        Instant now;
        MovableClock(String utc) { this.now = Instant.parse(utc); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void at(String utc) { now = Instant.parse(utc); }
    }

    private final List<Sent> sent = new ArrayList<>();

    private StatusDigest digest(MovableClock clock) {
        return new StatusDigest((title, message, priority, tags) -> { sent.add(new Sent(title, message, priority)); return true; },
                () -> "open body", () -> "close body", clock);
    }

    private List<String> titles() {
        return sent.stream().map(Sent::title).toList();
    }

    @Test
    void theOpenAndCloseSummariesGoOutOnceEachAfterTheirTimeInNewYork() {
        MovableClock clock = new MovableClock("2026-10-19T13:30:00Z"); // Monday 09:30 in New York (EDT)
        StatusDigest digest = digest(clock);

        digest.tick();
        assertTrue(sent.isEmpty(), "nothing before 09:35");

        clock.at("2026-10-19T13:36:00Z");
        digest.tick();
        digest.tick();
        assertEquals(1, sent.size(), "the open summary, once: " + titles());
        assertTrue(sent.get(0).title().contains("OPEN") && sent.get(0).message().equals("open body"), sent.toString());

        clock.at("2026-10-19T19:00:00Z"); // 15:00
        digest.tick();
        assertEquals(1, sent.size(), "not the close yet");

        clock.at("2026-10-19T20:11:00Z"); // 16:11
        digest.tick();
        digest.tick();
        assertEquals(2, sent.size(), titles().toString());
        assertTrue(sent.get(1).title().contains("CLOSE") && sent.get(1).message().equals("close body"));

        clock.at("2026-10-20T13:40:00Z"); // the next morning
        digest.tick();
        assertEquals(3, sent.size(), "a new day, a new open summary");
    }

    private StatusDigest calendarDigest(MovableClock clock) {
        return new StatusDigest((title, message, priority, tags) -> { sent.add(new Sent(title, message, priority)); return true; },
                () -> "open body", () -> "close body", clock, new com.sbk.optionspricer.market.MarketCalendar());
    }

    @Test
    void anExchangeHolidayGetsNoSummariesWithACalendar() {
        MovableClock clock = new MovableClock("2026-11-26T14:00:00Z"); // Thanksgiving, 09:00 in New York
        StatusDigest digest = calendarDigest(clock);

        clock.at("2026-11-26T14:40:00Z");
        digest.tick();
        clock.at("2026-11-26T21:15:00Z");
        digest.tick();

        assertTrue(sent.isEmpty(), "the market is shut: " + titles());
    }

    @Test
    void anEarlyCloseDaySendsTheCloseSummaryTenMinutesAfterOneOClock() {
        MovableClock clock = new MovableClock("2026-11-27T14:00:00Z"); // the day after Thanksgiving, 09:00 in New York
        StatusDigest digest = calendarDigest(clock);

        clock.at("2026-11-27T14:40:00Z");
        digest.tick();
        assertEquals(1, sent.size(), "the open summary: " + titles());

        clock.at("2026-11-27T18:05:00Z"); // 13:05
        digest.tick();
        assertEquals(1, sent.size(), "ten minutes after the close, not before");

        clock.at("2026-11-27T18:11:00Z"); // 13:11
        digest.tick();
        assertEquals(2, sent.size(), titles().toString());
        assertTrue(sent.get(1).title().contains("CLOSE"));
    }

    @Test
    void thereIsNothingAtTheWeekend() {
        MovableClock clock = new MovableClock("2026-10-17T14:00:00Z"); // Saturday
        StatusDigest digest = digest(clock);

        digest.tick();
        clock.at("2026-10-18T21:00:00Z"); // Sunday
        digest.tick();

        assertTrue(sent.isEmpty(), titles().toString());
    }

    @Test
    void theTimesFollowNewYorkThroughTheChangeToWinterTime() {
        // after 1 November 2026 New York is UTC-5: 09:35 is 14:35 UTC, not 13:35
        MovableClock clock = new MovableClock("2026-11-02T13:50:00Z"); // 08:50 in New York
        StatusDigest digest = digest(clock);

        digest.tick();
        assertTrue(sent.isEmpty(), "08:50 is before the open: " + titles());

        clock.at("2026-11-02T14:36:00Z"); // 09:36
        digest.tick();
        assertEquals(1, sent.size(), titles().toString());
    }

    @Test
    void anEngineStartedAfterTheOpenDoesNotSendALateOpenSummaryButStillSendsTheClose() {
        MovableClock clock = new MovableClock("2026-10-19T14:30:00Z"); // 10:30, after the open summary's time
        StatusDigest digest = digest(clock);

        digest.tick();
        assertTrue(sent.isEmpty(), "the startup message covers this; no second one: " + titles());

        clock.at("2026-10-19T20:12:00Z");
        digest.tick();
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).title().contains("CLOSE"));
    }

    @Test
    void theStartupMessageGoesOutAtOnceWithItsBody() {
        StatusDigest digest = digest(new MovableClock("2026-10-19T06:00:00Z"));

        digest.sendStartup("trading active; reconcile OK");

        assertEquals(1, sent.size());
        assertTrue(sent.get(0).title().contains("STARTED") && sent.get(0).message().equals("trading active; reconcile OK"), sent.toString());
    }

    @Test
    void aSummaryThatCannotBeBuiltIsReportedAsSuchInsteadOfBeingSkipped() {
        MovableClock clock = new MovableClock("2026-10-19T13:20:00Z"); // built before the open summary's time, so it is due
        StatusDigest due = new StatusDigest((title, message, priority, tags) -> { sent.add(new Sent(title, message, priority)); return true; },
                () -> { throw new IllegalStateException("journal unreadable"); }, () -> "close body", clock);

        clock.at("2026-10-19T13:36:00Z");
        due.tick();

        assertEquals(1, sent.size(), "a missing summary is itself worth knowing: " + titles());
        assertTrue(sent.get(0).message().contains("unavailable") && sent.get(0).message().contains("IllegalStateException"), sent.get(0).message());
    }
}
