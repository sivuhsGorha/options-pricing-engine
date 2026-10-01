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
 * Publishes the core risk state to a memory-mapped file for Zero-GC Inter-Process Communication (IPC).
 * The Web/REST API process reads this file to serve the frontend, ensuring the critical path JVM
 * is fully isolated from HTTP and JSON serialization garbage.
 */
public class MmapStatePublisher {
    private static final long FILE_SIZE = 32; // 4 doubles (delta, gamma, vega, margin)
    private static final VarHandle VH_DOUBLE = ValueLayout.JAVA_DOUBLE.varHandle();

    private final MemorySegment mappedSegment;
    private final Arena arena;

    private String getFilePath() {
        return System.getProperty("MMAP_STATE_FILE") != null ? 
            System.getProperty("MMAP_STATE_FILE") : 
            System.getenv("MMAP_STATE_FILE") != null ? 
            System.getenv("MMAP_STATE_FILE") : "data/quant_engine_state.dat";
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
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize mmap state file", e);
        }
    }

    /**
     * Writes risk metrics directly to off-heap memory (Zero Allocation).
     */
    public void publishRiskState(double netDelta, double netGamma, double netVega, double spanMargin) {
        VH_DOUBLE.setVolatile(mappedSegment, 0L, netDelta);
        VH_DOUBLE.setVolatile(mappedSegment, 8L, netGamma);
        VH_DOUBLE.setVolatile(mappedSegment, 16L, netVega);
        VH_DOUBLE.setVolatile(mappedSegment, 24L, spanMargin);
    }

    public void close() {
        arena.close();
    }
}
