package com.sbk.optionspricer.web;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.lang.invoke.VarHandle;
import com.sbk.optionspricer.core.MmapSecurityUtils;

public class MmapStateReader {
    private static final long FILE_SIZE = 56;
    private static final long MAGIC_VERSION = 0xAAAA0001L;

    private static final VarHandle VH_LONG = ValueLayout.JAVA_LONG.varHandle();
    private static final VarHandle VH_DOUBLE = ValueLayout.JAVA_DOUBLE.varHandle();

    private final MemorySegment mappedSegment;
    private final Arena arena;

    public static class RiskState {
        public final double netDelta;
        public final double netGamma;
        public final double netVega;
        public final double scenarioMargin;

        public RiskState(double netDelta, double netGamma, double netVega, double scenarioMargin) {
            this.netDelta = netDelta;
            this.netGamma = netGamma;
            this.netVega = netVega;
            this.scenarioMargin = scenarioMargin;
        }
    }

    private String getFilePath() {
        String raw = System.getProperty("MMAP_STATE_FILE") != null ? 
            System.getProperty("MMAP_STATE_FILE") : 
            System.getenv("MMAP_STATE_FILE") != null ? 
            System.getenv("MMAP_STATE_FILE") : "data/shm_state.dat";
        return MmapSecurityUtils.validateMmapPath(raw).toString();
    }

    public MmapStateReader() {
        this.arena = Arena.ofShared();
        try {
            File file = new File(getFilePath());
            if (!file.exists() || file.length() < FILE_SIZE) {
                throw new IllegalStateException("UNAVAILABLE");
            }

            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                try (FileChannel channel = raf.getChannel()) {
                    this.mappedSegment = channel.map(FileChannel.MapMode.READ_ONLY, 0, FILE_SIZE, arena);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("UNAVAILABLE", e);
        }
    }

    protected MmapStateReader(boolean mock) {
        this.arena = null;
        this.mappedSegment = null;
    }

    public RiskState readState() {
        if (mappedSegment == null) throw new IllegalStateException("UNAVAILABLE");

        int maxRetries = 1000;
        for (int i = 0; i < maxRetries; i++) {
            long seq1 = (long) VH_LONG.getAcquire(mappedSegment, 0L);
            if ((seq1 & 1) == 1) { // odd => write in progress
                Thread.onSpinWait();
                continue;
            }

            long magic = (long) VH_LONG.getAcquire(mappedSegment, 8L);
            if (magic != MAGIC_VERSION) {
                System.err.println("MmapStateReader fail: magic mismatch. Expected " + MAGIC_VERSION + " got " + magic);
                throw new IllegalStateException("UNAVAILABLE");
            }

            long heartbeat = (long) VH_LONG.getAcquire(mappedSegment, 16L);
            double delta = (double) VH_DOUBLE.getAcquire(mappedSegment, 24L);
            double gamma = (double) VH_DOUBLE.getAcquire(mappedSegment, 32L);
            double vega = (double) VH_DOUBLE.getAcquire(mappedSegment, 40L);
            double scenarioMargin = (double) VH_DOUBLE.getAcquire(mappedSegment, 48L);

            long seq2 = (long) VH_LONG.getAcquire(mappedSegment, 0L);
            if (seq1 == seq2) {
                // Check stale heartbeat (older than 2 seconds)
                if (System.currentTimeMillis() - heartbeat > 2000L) {
                    System.err.println("MmapStateReader fail: stale heartbeat. Now=" + System.currentTimeMillis() + ", heartbeat=" + heartbeat + ", diff=" + (System.currentTimeMillis() - heartbeat));
                    throw new IllegalStateException("UNAVAILABLE");
                }
                return new RiskState(delta, gamma, vega, scenarioMargin);
            }
            Thread.onSpinWait();
        }
        System.err.println("MmapStateReader fail: max retries exceeded");
        throw new IllegalStateException("UNAVAILABLE");
    }

    public void close() {
        arena.close();
    }
}
