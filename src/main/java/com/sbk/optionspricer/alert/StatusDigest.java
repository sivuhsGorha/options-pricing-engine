package com.sbk.optionspricer.alert;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

/**
 * What the phone hears from the engine without anyone asking: a message when it starts (an unexpected one means it restarted: a
 * reboot, a crash, a sleeping laptop that woke), a summary shortly after the US market opens, and one shortly after it closes, on
 * weekdays, once each. There is no market calendar, so an exchange holiday still gets its two summaries, saying the market is stale.
 * A summary that cannot be built is reported as unavailable, not skipped: its absence would otherwise be the only sign.
 */
public final class StatusDigest implements AutoCloseable {

    static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    static final LocalTime OPEN_AT = LocalTime.of(9, 35);
    static final LocalTime CLOSE_AT = LocalTime.of(16, 10);
    /** A summary is sent late (a laptop that was asleep) only within this long of its time; after that it would be misleading. */
    static final Duration GRACE = Duration.ofMinutes(60);
    private static final long POLL_MILLIS = 30_000L;

    private final Notifier notifier;
    private final Supplier<String> openBody;
    private final Supplier<String> closeBody;
    private final Clock clock;
    private LocalDate openSentOn;
    private LocalDate closeSentOn;
    private Thread worker;
    private volatile boolean closed;

    public StatusDigest(Notifier notifier, Supplier<String> openBody, Supplier<String> closeBody, Clock clock) {
        if (notifier == null || openBody == null || closeBody == null || clock == null) {
            throw new IllegalArgumentException("notifier, both bodies and clock are required");
        }
        this.notifier = notifier;
        this.openBody = openBody;
        this.closeBody = closeBody;
        this.clock = clock;
        // An engine that starts after a summary's time has just sent its startup message, which says the same; it does not also send that
        // summary late.
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), MARKET_ZONE);
        if (!now.toLocalTime().isBefore(OPEN_AT)) {
            openSentOn = now.toLocalDate();
        }
        if (!now.toLocalTime().isBefore(CLOSE_AT)) {
            closeSentOn = now.toLocalDate();
        }
    }

    public void sendStartup(String body) {
        notifier.send("ENGINE STARTED", body, "default", "rocket");
    }

    /** Sends whichever summary is due. Called every half minute by the worker. */
    public synchronized void tick() {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), MARKET_ZONE);
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return;
        }
        LocalDate today = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        if (!today.equals(openSentOn) && within(time, OPEN_AT)) {
            openSentOn = today;
            notifier.send("MARKET OPEN", bodyOf(openBody), "default", "bell");
        }
        if (!today.equals(closeSentOn) && within(time, CLOSE_AT)) {
            closeSentOn = today;
            notifier.send("MARKET CLOSE", bodyOf(closeBody), "default", "checkered_flag");
        }
    }

    private static boolean within(LocalTime time, LocalTime at) {
        return !time.isBefore(at) && time.isBefore(at.plus(GRACE));
    }

    private static String bodyOf(Supplier<String> body) {
        try {
            return body.get();
        } catch (RuntimeException e) {
            return "status unavailable: " + e.getClass().getSimpleName();
        }
    }

    public synchronized void start() {
        if (worker != null) {
            return;
        }
        worker = new Thread(() -> {
            while (!closed) {
                try {
                    tick();
                } catch (RuntimeException e) {
                    System.err.println("[ALERT] status summary failed: " + e.getClass().getSimpleName());
                }
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "alert-status-digest");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void close() {
        closed = true;
        Thread w;
        synchronized (this) {
            w = worker;
        }
        if (w != null) {
            w.interrupt();
        }
    }
}
