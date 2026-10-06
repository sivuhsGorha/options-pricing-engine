package com.sbk.optionspricer.web;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BrowserSessionManagerHardeningTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ORIGIN = "http://localhost:8080";

    /** Fast hashing and a fake clock so these tests are quick and deterministic. */
    private static BrowserSessionManager manager(AtomicLong clock, int maxSessions) {
        return new BrowserSessionManager(PASSWORD, ORIGIN, clock::get, 1_000, maxSessions);
    }

    @Test
    void loginAttemptTrackingDoesNotGrowWithoutBound() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        BrowserSessionManager sessions = manager(clock, 1_000);

        for (int i = 0; i < 3_000; i++) {
            sessions.login("wrong", "client-" + i);
        }
        assertTrue(sessions.trackedLoginClients() >= 1_000, "precondition: many distinct clients tracked");

        clock.addAndGet(61_000L); // every window has expired
        sessions.login("wrong", "one-more-client");

        assertTrue(sessions.trackedLoginClients() <= 10,
                "expired login windows must be evicted, but " + sessions.trackedLoginClients() + " remain");
    }

    @Test
    void perClientRateLimitStillAppliesAndAnotherClientIsUnaffected() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        BrowserSessionManager sessions = manager(clock, 1_000);

        for (int i = 0; i < 5; i++) {
            assertFalse(sessions.login("wrong", "attacker").rateLimited());
        }
        assertTrue(sessions.login("wrong", "attacker").rateLimited());
        assertTrue(sessions.login(PASSWORD, "attacker").rateLimited(), "even the right password is limited");

        BrowserSessionManager.LoginResult other = sessions.login(PASSWORD, "operator");
        assertFalse(other.rateLimited());
        assertNotNull(other.sessionToken());
    }

    @Test
    void expiredSessionsAreSweptOnLogin() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        BrowserSessionManager sessions = manager(clock, 1_000);
        for (int i = 0; i < 5; i++) {
            assertNotNull(sessions.login(PASSWORD, "operator-" + i).sessionToken());
        }
        assertEquals(5, sessions.sessionCount());

        clock.addAndGet((BrowserSessionManager.SESSION_MAX_AGE_SECONDS + 1) * 1000L);
        assertNotNull(sessions.login(PASSWORD, "operator-new").sessionToken());

        assertEquals(1, sessions.sessionCount(), "sessions that were never presented again must still be removed");
    }

    @Test
    void sessionCountIsCappedAndFailsClosed() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        BrowserSessionManager sessions = manager(clock, 3);

        for (int i = 0; i < 3; i++) {
            assertNotNull(sessions.login(PASSWORD, "operator-" + i).sessionToken());
        }
        BrowserSessionManager.LoginResult fourth = sessions.login(PASSWORD, "operator-3");
        assertTrue(fourth.rateLimited(), "no new session while the table is full of live sessions");
        assertNull(fourth.sessionToken());
        assertEquals(3, sessions.sessionCount());
    }

    @Test
    void forwardedHeaderIsIgnoredUnlessTheDirectPeerIsATrustedProxy() throws Exception {
        InetSocketAddress client = new InetSocketAddress(InetAddress.getByName("203.0.113.9"), 5555);
        InetSocketAddress proxy = new InetSocketAddress(InetAddress.getByName("10.0.0.5"), 5555);
        Set<String> trusted = Set.of("10.0.0.5");

        assertEquals("203.0.113.9", ClientAddressResolver.resolve(client, "1.2.3.4", trusted),
                "an untrusted peer cannot choose its own rate-limit identity");
        assertEquals("198.51.100.7", ClientAddressResolver.resolve(proxy, "198.51.100.7", trusted));
        assertEquals("198.51.100.7", ClientAddressResolver.resolve(proxy, "6.6.6.6, 198.51.100.7", trusted),
                "the entry the trusted proxy appended is used, not what the client claimed first");
        assertEquals("198.51.100.7", ClientAddressResolver.resolve(proxy, "198.51.100.7, 10.0.0.5", trusted),
                "trusted hops at the end of the chain are skipped");
        assertEquals("10.0.0.5", ClientAddressResolver.resolve(proxy, null, trusted));
        assertEquals("10.0.0.5", ClientAddressResolver.resolve(proxy, "not an ip; DROP TABLE", trusted));
        assertEquals("203.0.113.9", ClientAddressResolver.resolve(client, null, Set.of()));
        assertEquals("unknown", ClientAddressResolver.resolve(null, "1.2.3.4", trusted));
    }
}
