package com.sbk.optionspricer.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.lang.invoke.VarHandle;

/**
 * Publishes the core risk state to a memory-mapped file under a seqlock (an odd sequence marks a write in
 * progress, so a reader retries rather than see a torn record). The dashboard reads it through a second read-only
 * mapping in the SAME JVM: there is no second process, so this demonstrates the technique and does not isolate the
 * engine from HTTP or JSON garbage. The file would let a separate reader process attach unchanged.
 */
public class MmapStatePublisher {
    private static final long FILE_SIZE = 56;
    private static final long MAGIC_VERSION = 0xAAAA0001L;

    private static final VarHandle VH_LONG = ValueLayout.JAVA_LONG.varHandle();
    private static final VarHandle VH_DOUBLE = ValueLayout.JAVA_DOUBLE.varHandle();

    private final MemorySegment mappedSegment;
    private final Arena arena;

    private String getFilePath() {
        String raw = System.getProperty("MMAP_STATE_FILE") != null ? 
            System.getProperty("MMAP_STATE_FILE") : 
            com.sbk.optionspricer.config.EnvironmentConfigLoader.getOrDefault("MMAP_STATE_FILE", "data/shm_state.dat");
        return MmapSecurityUtils.validateMmapPath(raw).toString();
    }

    public MmapStatePublisher() {
        this.arena = Arena.ofShared();
        try {
            File file = new File(getFilePath());
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            if (!file.exists()) {
                file.createNewFile();
            }
            MmapSecurityUtils.secureMmapFile(file.toPath());
            
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.setLength(FILE_SIZE); // Ensure file is right size
                try (FileChannel channel = raf.getChannel()) {
                    this.mappedSegment = channel.map(FileChannel.MapMode.READ_WRITE, 0, FILE_SIZE, arena);
                }
            }
            
            // Initialize Publisher state
            VH_LONG.setVolatile(mappedSegment, 0L, 0L); // Sequence
            VH_LONG.setVolatile(mappedSegment, 8L, MAGIC_VERSION);
            
        } catch (IOException | RuntimeException e) {
            arena.close(); // do not leak the arena (and the mapping) when initialization fails
            throw new RuntimeException("Failed to initialize mmap state file", e);
        }
    }

    /**
     * Writes risk metrics directly to off-heap memory using a Seqlock for zero torn reads.
     */
    public synchronized void publishRiskState(double netDelta, double netGamma, double netVega, double scenarioMargin) {
        long seq = (long) VH_LONG.getOpaque(mappedSegment, 0L);
        seq++; // make it odd to signal write in progress
        VH_LONG.setRelease(mappedSegment, 0L, seq);

        VH_LONG.setRelease(mappedSegment, 16L, System.currentTimeMillis()); // Heartbeat
        VH_DOUBLE.setRelease(mappedSegment, 24L, netDelta);
        VH_DOUBLE.setRelease(mappedSegment, 32L, netGamma);
        VH_DOUBLE.setRelease(mappedSegment, 40L, netVega);
        VH_DOUBLE.setRelease(mappedSegment, 48L, scenarioMargin);

        seq++; // make it even to signal write complete
        VH_LONG.setRelease(mappedSegment, 0L, seq);
    }

    public void publishUnavailable() {
        // Invalidate magic number to immediately mark state as UNAVAILABLE
        VH_LONG.setRelease(mappedSegment, 8L, 0L);
    }

    public void close() {
        if (arena != null && arena.scope().isAlive()) {
            if (mappedSegment != null) {
                mappedSegment.fill((byte) 0);
            }
            arena.close();
        }
    }
}
