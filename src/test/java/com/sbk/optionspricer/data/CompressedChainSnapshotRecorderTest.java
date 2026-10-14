package com.sbk.optionspricer.data;

import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionChainProvider;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CompressedChainSnapshotRecorderTest {

    @Test
    void recordsCompressedQuotesAndReaderReconstructsChains(@TempDir Path tempDir) throws Exception {
        Instant t0 = Instant.parse("2026-10-14T14:30:00Z");
        Clock clock = Clock.fixed(t0, ZoneOffset.UTC);

        LocalDate expiryNov = LocalDate.of(2026, 11, 20);
        LocalDate expiryDec = LocalDate.of(2026, 12, 18);

        List<OptionQuote> novQuotes = List.of(
                new OptionQuote("SPY", expiryNov, 500.0, OptionType.CALL, 82.10, 82.40, 0.195, 1200, 15000),
                new OptionQuote("SPY", expiryNov, 500.0, OptionType.PUT, 2.15, 2.20, 0.190, 850, 11200)
        );
        List<OptionQuote> decQuotes = List.of(
                new OptionQuote("SPY", expiryDec, 510.0, OptionType.CALL, 75.30, 75.80, 0.201, 400, 8000),
                new OptionQuote("SPY", expiryDec, 510.0, OptionType.PUT, 5.40, 5.50, 0.198, 300, 6200)
        );

        OptionChain chainNov = new OptionChain("SPY", expiryNov, 580.0, novQuotes);
        OptionChain chainDec = new OptionChain("SPY", expiryDec, 580.0, decQuotes);

        try (CompressedChainSnapshotRecorder recorder = new CompressedChainSnapshotRecorder(tempDir, clock)) {
            boolean enqueued = recorder.record(List.of(chainNov, chainDec), "CBOE_DELAYED", t0);
            assertTrue(enqueued, "task must be enqueued");
            assertTrue(recorder.flush(5, TimeUnit.SECONDS), "flush must complete");

            assertEquals(4, recorder.totalQuotesRecorded());
            assertEquals(1, recorder.totalFilesWritten());
        }

        List<Path> files = ChainSnapshotReader.listDailySnapshotFiles(tempDir, "SPY", LocalDate.of(2026, 10, 14));
        assertEquals(1, files.size(), "exactly one snapshot file written");
        Path snapshotFile = files.get(0);
        assertTrue(snapshotFile.getFileName().toString().endsWith(".csv.gz"));

        List<CompressedChainSnapshotRecorder.RecordedQuote> quotes = ChainSnapshotReader.readQuotes(snapshotFile);
        assertEquals(4, quotes.size());

        CompressedChainSnapshotRecorder.RecordedQuote q0 = quotes.get(0);
        assertEquals("SPY", q0.symbol());
        assertEquals(expiryNov, q0.expiry());
        assertEquals(500.0, q0.strike(), 1e-6);
        assertEquals("CALL", q0.type());
        assertEquals(580.0, q0.spot(), 1e-6);
        assertEquals(82.10, q0.bid(), 1e-6);
        assertEquals(82.40, q0.ask(), 1e-6);
        assertEquals(82.25, q0.mid(), 1e-6);
        assertEquals("CBOE_DELAYED", q0.source());
        assertEquals("SPY261120C00500000", q0.contractSymbol());

        List<OptionChain> reconstructed = ChainSnapshotReader.readChains(snapshotFile);
        assertEquals(2, reconstructed.size());
        assertEquals(expiryNov, reconstructed.get(0).expiry());
        assertEquals(2, reconstructed.get(0).quotes().size());
        assertEquals(expiryDec, reconstructed.get(1).expiry());
        assertEquals(2, reconstructed.get(1).quotes().size());
    }

    @Test
    void closingWritesWhatIsStillQueuedInsteadOfAbandoningIt(@TempDir Path tempDir) throws Exception {
        Instant t0 = Instant.parse("2026-10-14T14:30:00Z");
        LocalDate expiry = LocalDate.of(2026, 11, 20);
        OptionChain chain = new OptionChain("SPY", expiry, 580.0, List.of(
                new OptionQuote("SPY", expiry, 580.0, OptionType.CALL, 12.0, 12.2, 0.18, 50, 100)));

        CompressedChainSnapshotRecorder recorder = new CompressedChainSnapshotRecorder(tempDir, Clock.fixed(t0, ZoneOffset.UTC));
        assertTrue(recorder.record(List.of(chain), "CBOE_DELAYED", t0));
        recorder.close(); // no flush first: shutdown is exactly when the last minute would be lost

        assertEquals(1, recorder.totalFilesWritten(), "the queued snapshot must be on disk once close() returns");
        assertEquals(1, ChainSnapshotReader.listDailySnapshotFiles(tempDir, "SPY", LocalDate.of(2026, 10, 14)).size());
    }

    @Test
    void itRemembersWhenItLastWroteAFileSoTheDashboardCanTellALiveRecorderFromAStalledOne(@TempDir Path tempDir) throws Exception {
        Instant t0 = Instant.parse("2026-10-14T14:30:00Z");
        LocalDate expiry = LocalDate.of(2026, 11, 20);
        OptionChain chain = new OptionChain("SPY", expiry, 580.0, List.of(
                new OptionQuote("SPY", expiry, 580.0, OptionType.CALL, 12.0, 12.2, 0.18, 50, 100)));

        try (CompressedChainSnapshotRecorder recorder = new CompressedChainSnapshotRecorder(tempDir, Clock.fixed(t0, ZoneOffset.UTC))) {
            assertTrue(recorder.lastWrittenAt().isEmpty(), "nothing written yet");
            recorder.record(List.of(chain), "CBOE_DELAYED", t0);
            recorder.flush(5, TimeUnit.SECONDS);

            assertEquals(t0, recorder.lastWrittenAt().orElseThrow());
        }
    }

    @Test
    void aFeedThatHasNotMovedIsNotRecordedAgain(@TempDir Path tempDir) throws Exception {
        // Overnight and at weekends the provider serves the same document every minute; keeping each copy would fill
        // the disk with identical files and make a replay look like it had data it did not.
        Instant t0 = Instant.parse("2026-10-14T03:00:00Z");
        LocalDate expiry = LocalDate.of(2026, 11, 20);
        OptionChain chain = new OptionChain("SPY", expiry, 580.0, List.of(
                new OptionQuote("SPY", expiry, 580.0, OptionType.CALL, 12.0, 12.2, 0.18, 50, 100)));
        Instant feedStamp = Instant.parse("2026-10-13T20:00:00Z");
        OptionChainProvider.SourcedChain first = new OptionChainProvider.SourcedChain(chain, "CBOE_DELAYED", true, List.of(), feedStamp);
        OptionChainProvider.SourcedChain same = new OptionChainProvider.SourcedChain(chain, "CBOE_DELAYED", true, List.of(), feedStamp);
        OptionChainProvider.SourcedChain moved = new OptionChainProvider.SourcedChain(chain, "CBOE_DELAYED", true, List.of(),
                feedStamp.plusSeconds(60));

        try (CompressedChainSnapshotRecorder recorder = new CompressedChainSnapshotRecorder(tempDir, Clock.fixed(t0, ZoneOffset.UTC))) {
            assertTrue(recorder.recordSourced(List.of(first), t0));
            assertFalse(recorder.recordSourced(List.of(same), t0.plusSeconds(60)), "same feed timestamp: nothing new to keep");
            assertTrue(recorder.recordSourced(List.of(moved), t0.plusSeconds(120)), "the feed moved: record it");
            recorder.flush(5, TimeUnit.SECONDS);

            assertEquals(2, recorder.totalFilesWritten());
            assertEquals(1, recorder.skippedUnchanged());
        }
    }

    @Test
    void aFeedWithNoTimestampIsAlwaysRecordedBecauseItsAgeIsUnknown(@TempDir Path tempDir) throws Exception {
        Instant t0 = Instant.parse("2026-10-14T15:00:00Z");
        LocalDate expiry = LocalDate.of(2026, 11, 20);
        OptionChain chain = new OptionChain("SPY", expiry, 580.0, List.of(
                new OptionQuote("SPY", expiry, 580.0, OptionType.CALL, 12.0, 12.2, 0.18, 50, 100)));
        OptionChainProvider.SourcedChain undated = new OptionChainProvider.SourcedChain(chain, "CBOE_DELAYED", true);

        try (CompressedChainSnapshotRecorder recorder = new CompressedChainSnapshotRecorder(tempDir, Clock.fixed(t0, ZoneOffset.UTC))) {
            assertTrue(recorder.recordSourced(List.of(undated), t0));
            assertTrue(recorder.recordSourced(List.of(undated), t0.plusSeconds(60)));
            recorder.flush(5, TimeUnit.SECONDS);

            assertEquals(2, recorder.totalFilesWritten());
            assertEquals(0, recorder.skippedUnchanged());
        }
    }

    @Test
    void recordsSourcedChainsPreservingProvenance(@TempDir Path tempDir) throws Exception {
        Instant t0 = Instant.parse("2026-10-14T15:00:00Z");
        Clock clock = Clock.fixed(t0, ZoneOffset.UTC);

        LocalDate expiry = LocalDate.of(2026, 11, 20);
        OptionChain chain = new OptionChain("SPY", expiry, 580.0, List.of(
                new OptionQuote("SPY", expiry, 580.0, OptionType.CALL, 12.0, 12.2, 0.18, 50, 100)
        ));
        OptionChainProvider.SourcedChain sourced = new OptionChainProvider.SourcedChain(chain, "CBOE_DELAYED", true);

        try (CompressedChainSnapshotRecorder recorder = new CompressedChainSnapshotRecorder(tempDir, clock)) {
            recorder.recordSourced(List.of(sourced), t0);
            recorder.flush(5, TimeUnit.SECONDS);

            assertEquals(1, recorder.totalQuotesRecorded());
            assertEquals(1, recorder.totalFilesWritten());
        }

        List<Path> files = ChainSnapshotReader.listDailySnapshotFiles(tempDir, "SPY", LocalDate.of(2026, 10, 14));
        assertEquals(1, files.size());
        List<CompressedChainSnapshotRecorder.RecordedQuote> quotes = ChainSnapshotReader.readQuotes(files.get(0));
        assertEquals(1, quotes.size());
        assertEquals("CBOE_DELAYED", quotes.get(0).source());
    }
}
