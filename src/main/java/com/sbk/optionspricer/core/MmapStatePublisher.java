package com.sbk.optionspricer.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;

/**
 * Publishes the core risk state to a memory-mapped file for Zero-GC Inter-Process Communication (IPC).
 * The Web/REST API process reads this file to serve the frontend, ensuring the critical path JVM
 * is fully isolated from HTTP and JSON serialization garbage.
 */
public class MmapStatePublisher {
    private static final String FILE_PATH = "target/quant_engine_state.dat";
    private static final long FILE_SIZE = 32; // 4 doubles (delta, gamma, vega, margin)

    private final MemorySegment mappedSegment;
    private final Arena arena;

    public MmapStatePublisher() {
        this.arena = Arena.ofShared();
        try {
            File file = new File(FILE_PATH);
            file.getParentFile().mkdirs();
            
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
        mappedSegment.set(ValueLayout.JAVA_DOUBLE, 0, netDelta);
        mappedSegment.set(ValueLayout.JAVA_DOUBLE, 8, netGamma);
        mappedSegment.set(ValueLayout.JAVA_DOUBLE, 16, netVega);
        mappedSegment.set(ValueLayout.JAVA_DOUBLE, 24, spanMargin);
    }

    public void close() {
        arena.close();
    }
}
